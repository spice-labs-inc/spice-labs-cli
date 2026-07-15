// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.cli;

import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.spicelabs.cli.spi.SpiceCommandPlugin;
import io.spicelabs.cli.spi.SpiceContext;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Discovers {@link SpiceCommandPlugin} providers via {@link ServiceLoader} and mounts
 * them as top-level subcommands. A plugin is included purely by being on the classpath;
 * {@code spice} has no compile-time knowledge of any plugin.
 *
 * <p>Loading is defensive: an incompatible API version, a {@code null} command, a name
 * that clashes with an already-registered command, or any exception thrown while a
 * plugin builds its command causes that single plugin to be skipped with a warning —
 * never a CLI-wide failure.
 */
public final class PluginLoader {

  private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

  private PluginLoader() {}

  /** Discover plugins via {@link ServiceLoader} and register them on {@code cmd}. */
  public static void registerPlugins(CommandLine cmd, SpiceContext context) {
    registerPlugins(cmd, context, ServiceLoader.load(SpiceCommandPlugin.class));
  }

  /** Register the supplied plugins on {@code cmd}. Visible for testing. */
  static void registerPlugins(CommandLine cmd, SpiceContext context,
                              Iterable<SpiceCommandPlugin> plugins) {
    for (SpiceCommandPlugin plugin : plugins) {
      String id = safeId(plugin);
      try {
        int pluginApi = plugin.apiVersion();
        if (pluginApi != SpiceContext.API_VERSION) {
          log.warn("Skipping plugin '{}': built against SPI version {}, but this CLI provides version {}",
              id, pluginApi, SpiceContext.API_VERSION);
          continue;
        }

        Object command = plugin.command(context);
        if (command == null) {
          log.warn("Skipping plugin '{}': command() returned null", id);
          continue;
        }

        CommandLine sub = toCommandLine(command);
        String name = sub.getCommandName();
        String parent = plugin.parent();
        if (parent != null && !parent.isBlank()) {
          // Mount under a named parent subcommand (e.g. `survey`).
          CommandLine parentCmd = cmd.getSubcommands().get(parent);
          if (parentCmd == null) {
            log.warn("Skipping plugin '{}': parent command '{}' not found", id, parent);
            continue;
          }
          if (parentCmd.getSubcommands().containsKey(name)) {
            log.warn("Skipping plugin '{}': '{}' already exists under '{}'", id, name, parent);
            continue;
          }
          parentCmd.addSubcommand(name, sub);
          log.debug("Registered plugin '{}' as '{} {}'", id, parent, name);
          continue;
        }
        if (cmd.getSubcommands().containsKey(name)) {
          log.warn("Skipping plugin '{}': subcommand name '{}' is already registered", id, name);
          continue;
        }

        cmd.addSubcommand(name, sub);
        log.debug("Registered plugin '{}' as subcommand '{}'", id, name);
      } catch (Throwable t) {
        // Error isolation: one misbehaving plugin must never break the CLI.
        log.warn("Skipping plugin '{}': failed to load ({}: {})",
            id, t.getClass().getSimpleName(), t.getMessage());
      }
    }
  }

  /** Normalize a plugin's returned command (annotated object / CommandSpec / CommandLine). */
  private static CommandLine toCommandLine(Object command) {
    if (command instanceof CommandLine) {
      return (CommandLine) command;
    }
    if (command instanceof CommandSpec) {
      return new CommandLine((CommandSpec) command);
    }
    return new CommandLine(command);
  }

  private static String safeId(SpiceCommandPlugin plugin) {
    try {
      String id = plugin.id();
      if (id != null && !id.isBlank()) {
        return id;
      }
    } catch (Throwable ignored) {
      // fall through to class name
    }
    return plugin.getClass().getName();
  }
}
