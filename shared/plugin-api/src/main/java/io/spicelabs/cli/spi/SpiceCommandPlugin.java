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

package io.spicelabs.cli.spi;

/**
 * Service-provider interface for contributing a top-level {@code spice} subcommand.
 *
 * <p>Implementations are discovered at runtime via {@link java.util.ServiceLoader};
 * register the implementation in
 * {@code META-INF/services/io.spicelabs.cli.spi.SpiceCommandPlugin}. A plugin is
 * included in a {@code spice} distribution purely by being present on the classpath —
 * {@code spice} has no compile-time knowledge of any plugin.
 *
 * <p>The plugin <em>fully defines</em> its subcommand (name, description, options,
 * parameters, nested subcommands, and execution) through the object returned by
 * {@link #command(SpiceContext)}; {@code spice} only discovers it and mounts it.
 *
 * <p>This interface is published as the {@code io.spicelabs:spice-plugin-api} artifact
 * so that third parties can author plugins against a stable, dependency-free contract.
 */
public interface SpiceCommandPlugin {

  /**
   * Build the subcommand to mount under {@code spice}.
   *
   * <p>The returned value may be any picocli command:
   * <ul>
   *   <li>a {@code @picocli.CommandLine.Command}-annotated object that is
   *       {@link Runnable} or {@link java.util.concurrent.Callable},</li>
   *   <li>a {@code picocli.CommandLine} instance, or</li>
   *   <li>a {@code picocli.CommandLine.Model.CommandSpec}.</li>
   * </ul>
   * picocli reads the command name, description, options, parameters and nested
   * subcommands from it, so {@code spice} needs no compile-time knowledge of the
   * returned type. The return type is declared as {@link Object} precisely so this
   * API artifact carries no dependency on picocli.
   *
   * @param context shared services (CLI version, resolved {@code SPICE_PASS}) so a
   *                plugin can behave consistently with built-in commands
   * @return the picocli command definition; returning {@code null} skips the plugin
   */
  Object command(SpiceContext context);

  /**
   * A stable identifier for this plugin, used for ordering and for diagnostics when a
   * plugin is skipped (load failure, name clash, incompatible API version). Should be
   * unique and human-recognizable, e.g. {@code "allspice-registry"}.
   */
  String id();

  /**
   * The SPI contract version this plugin was built against. {@code spice} refuses to
   * mount a plugin whose version differs from {@link SpiceContext#API_VERSION}, rather
   * than risk a {@link LinkageError} at command-execution time.
   */
  default int apiVersion() {
    return SpiceContext.API_VERSION;
  }

  /**
   * Parent command name under which this plugin should be mounted, or an empty string
   * to mount as a top-level subcommand directly under {@code spice}.
   *
   * <p>For example, returning {@code "survey"} mounts the plugin's command as
   * {@code spice survey <command-name>}. Returning {@code ""} mounts it as
   * {@code spice <command-name>}.
   */
  default String parent() {
    return "";
  }

  /**
   * A PowerShell tab-completion fragment contributing completions for this plugin's
   * command tree, or an empty string for none (the default).
   *
   * <p>Why only PowerShell: {@code spice} generates bash/zsh completion automatically
   * from the live picocli command model (which already includes this plugin's command),
   * so no fragment is needed there. picocli cannot generate PowerShell, so plugins supply
   * that here; {@code spice} splices every plugin's fragment into the PowerShell
   * completion script it emits.
   *
   * <p>The fragment runs after {@code spice} has defined a {@code $SpiceCompletions}
   * hashtable (pre-populated with the built-in commands) and before the argument
   * completer is registered. It must add one entry keyed by the plugin's top-level
   * command name, whose value maps {@code __sub} to the subcommand names and each
   * subcommand name to its option/flag list, e.g.:
   *
   * <pre>{@code
   * $SpiceCompletions['registry'] = @{
   *   __sub    = @('discover', 'run', 'status')
   *   discover = @('--config', '--output', '--max', '--json')
   *   run      = @('--config', '--discovery', '--json')
   *   status   = @('--config', '--json')
   * }
   * }</pre>
   */
  default String powershellCompletion() {
    return "";
  }
}
