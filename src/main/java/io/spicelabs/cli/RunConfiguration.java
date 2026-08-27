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
import java.util.Map;
import java.util.Optional;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import io.spicelabs.goatrodeo.util.TomlTables;

/**
 * The run's configuration file, parsed once.
 *
 * <p>A command's settings live at its own command path — {@code spice survey inventory} reads
 * {@code [survey.inventory]}, the {@code registry} plugin reads {@code [registry]} — and a
 * component a command embeds gets a sub-table named after it. {@code spice} hands each plugin
 * only the table at its own path, so a plugin never sees the file, never learns where its
 * table sits, and never parses TOML that is not its own.
 *
 * <p>{@code spice} does not understand the tables it carries. That is the point: it is what
 * keeps this command free of every plugin's schema. The previous attempt at cross-program
 * configuration failed exactly there — an allowlist of another program's flags, maintained
 * here, that drifted until it permitted flags that program does not have.
 *
 * <p>Tables cross the plugin SPI as plain nested maps of {@code java.*} values, so
 * {@code spice-plugin-api} stays dependency-free. {@link TomlTables#toPlainMap} does that
 * conversion; {@link TomlTable#toMap()} is not usable for it because it is shallow and would
 * leave nested tables as tomlj objects.
 */
final class RunConfiguration {

  /** A run with no configuration file. */
  static final RunConfiguration EMPTY = new RunConfiguration(null, null);

  private static volatile RunConfiguration current = EMPTY;

  private final Path file;
  private final TomlTable root;

  private RunConfiguration(Path file, TomlTable root) {
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
    RunConfiguration loaded = new RunConfiguration(path, parsed);
    current = loaded;
    return loaded;
  }

  /**
   * The configuration file for this run.
   *
   * <p>A process-wide accessor for a process-wide fact, matching
   * {@link DefaultSpiceContext#current()}. Built-in commands need it and picocli constructs
   * them itself, so there is nowhere to hand it in. Plugins do not use this — they are given
   * their own table through {@link PluginContext}, which is what keeps them from reading
   * each other's settings.
   */
  static RunConfiguration current() {
    return current;
  }

  /** The file this was read from, if any. */
  Optional<Path> file() {
    return Optional.ofNullable(file);
  }

  /**
   * The table at the given command path, as plain {@code java.*} values.
   *
   * @param commandPath the command's path, e.g. {@code ["survey", "inventory"]} or
   *     {@code ["registry"]}
   * @return the table, or an empty map when there is no config file or no such table
   */
  Map<String, Object> tableFor(String... commandPath) {
    TomlTable table = root;
    for (String segment : commandPath) {
      if (table == null) {
        return Map.of();
      }
      table = table.getTable(segment);
    }
    return table == null ? Map.of() : TomlTables.toPlainMap(table);
  }
}
