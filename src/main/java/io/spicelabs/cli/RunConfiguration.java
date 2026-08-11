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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import io.spicelabs.config.Groups;
import io.spicelabs.config.Resolution;
import io.spicelabs.config.Resolver;
import io.spicelabs.goatrodeo.util.TomlTables;

/**
 * The run's configuration file, parsed once, and resolved per command.
 *
 * <p>Settings live in <em>groups</em> named for the job rather than for whoever does it —
 * {@code [analysis]}, {@code [upload]}, {@code [pipeline]}. A group is shared: a value
 * written once at the top level reaches every command that claims that group, so a user
 * configures the job and not each component that performs part of it. A same-named table
 * under a command's path overrides for that command alone, so {@code [registry.analysis]}
 * beats {@code [analysis]} for {@code spice registry} and nothing else.
 *
 * <p>Which group means what is still none of {@code spice}'s business. That is the property
 * that keeps this command free of every component's schema, and the previous attempt at
 * cross-program configuration failed exactly there — an allowlist of another program's
 * flags, maintained here, that drifted until it permitted flags that program does not have.
 *
 * <p>What is {@code spice}'s business is deciding which source wins, and saying so. The
 * layering, the environment-variable naming and the override reporting all live in
 * {@code spice-config}, in one implementation shared with every component, because rules
 * copied into three codebases are rules that will disagree.
 */
final class RunConfiguration {

  private static final Logger log = LoggerFactory.getLogger(RunConfiguration.class);

  /** The environment-variable prefix for settings when running as {@code spice}. */
  static final String ENVIRONMENT_PREFIX = "SPICE";

  /** A run with no configuration file. */
  static final RunConfiguration EMPTY = new RunConfiguration(null, Map.of());

  private static volatile RunConfiguration current = EMPTY;

  private final Path file;
  private final Map<String, Object> root;

  private RunConfiguration(Path file, Map<String, Object> root) {
    this.file = file;
    this.root = root;
  }

  /**
   * Read the configuration file for this run.
   *
   * @param explicit the value of {@code --config}, or null
   * @throws IllegalArgumentException if the file is missing or does not parse. A config file
   *     that cannot be read must stop the run: continuing would silently ignore every setting
   *     in it, which is worse than failing.
   */
  static RunConfiguration load(Path explicit) {
    Optional<Path> resolved = ConfigFile.resolve(explicit);
    if (resolved.isEmpty()) {
      return EMPTY;
    }
    Path path = resolved.get();
    TomlParseResult parsed;
    try {
      parsed = Toml.parse(Files.readString(path));
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Could not read config file " + path + ": " + e.getMessage(), e);
    }
    if (!parsed.errors().isEmpty()) {
      throw new IllegalArgumentException("Invalid config file " + path + ": " + parsed.errors().get(0));
    }
    RunConfiguration loaded = new RunConfiguration(path, TomlTables.toPlainMap(parsed));
    current = loaded;
    return loaded;
  }

  /**
   * The configuration file for this run.
   *
   * <p>A process-wide accessor for a process-wide fact, matching
   * {@link DefaultSpiceContext#current()}. Built-in commands need it and picocli constructs
   * them itself, so there is nowhere to hand it in. Plugins do not use this — they are given
   * their claimed groups through {@link PluginContext}, which is what keeps them from reading
   * each other's settings.
   */
  static RunConfiguration current() {
    return current;
  }

  /** The file this was read from, if any. */
  Optional<Path> file() {
    return Optional.ofNullable(file);
  }

  /** The parsed file, for whole-file checks such as {@link Groups#unclaimed}. */
  Map<String, Object> root() {
    return root;
  }

  /**
   * Resolve the groups a command claims.
   *
   * <p>The shared table, then the command-scoped one, then the environment. Command-line
   * flags are added by the caller afterwards, because only the command knows which of its
   * options map to which setting.
   *
   * @param commandPath the command's path, e.g. {@code ["registry"]} or
   *     {@code ["survey", "inventory"]}
   * @param groups the groups that command claims
   */
  Resolver resolverFor(List<String> commandPath, Collection<String> groups) {
    Resolver resolver =
        new Resolver(ENVIRONMENT_PREFIX, Set.copyOf(groups), message -> log.info("⚙️  {}", message));
    if (file != null) {
      resolver.withFile(file, root, commandPath);
    }
    return resolver.withEnvironment(System.getenv());
  }

  /**
   * The resolved values for a command's claimed groups, keyed by group name.
   *
   * <p>What crosses the plugin SPI, and what a built-in command hands to the component that
   * owns the group. A group of settings arrives as a map; a group that names a list of
   * things — an array of repositories — arrives as that list, since it has no keys to layer.
   */
  Map<String, Object> claimedGroups(List<String> commandPath, Collection<String> groups) {
    Resolution resolved = resolverFor(commandPath, groups).resolve();
    Map<String, Object> claimed = new java.util.LinkedHashMap<>(resolved.groups());
    for (String group : groups) {
      resolved.value(group).ifPresent(whole -> claimed.put(group, whole));
    }
    return claimed;
  }

  /** As {@link #claimedGroups}, but only the groups that are tables of settings. */
  Map<String, Map<String, Object>> groupsFor(List<String> commandPath, Collection<String> groups) {
    return resolverFor(commandPath, groups).resolve().groups();
  }

  /**
   * Warn about top-level tables no command reads.
   *
   * <p>A misspelt group is otherwise perfectly silent: the file parses, the run proceeds, and
   * the setting does nothing at all. Warned rather than refused, because an unknown table may
   * belong to a plugin that is not installed in this run, and a shared config file should
   * stay usable across machines with different plugins.
   */
  void warnAboutUnclaimedGroups(Collection<String> claimed, Collection<String> commands) {
    Groups.describeUnclaimed(Groups.unclaimed(root, claimed, commands))
        .ifPresent(message -> log.warn("⚠️  {}", message));
  }

  /** Everything resolved for a command, with provenance, for {@code spice config explain}. */
  Resolution explain(List<String> commandPath, Collection<String> groups) {
    return resolverFor(commandPath, groups).resolve();
  }
}
