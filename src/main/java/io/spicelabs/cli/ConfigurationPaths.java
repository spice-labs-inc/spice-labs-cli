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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.spicelabs.cli.spi.SpiceCommandPlugin;
import io.spicelabs.config.TomlFile;

/**
 * The paths a configuration file names, for the wrapper to bind-mount.
 *
 * <p>The wrapper mounts what it can see. Until now that meant arguments, because reading a
 * TOML file from a shell script means either shipping a parser in bash or guessing — and
 * guessing about which values are paths is how a run ends up writing its output inside a
 * container that is about to be discarded.
 *
 * <p>So the parsing happens here, where a TOML parser already exists and where each
 * component's own declaration of its path-valued keys is available: built-in commands state
 * theirs below, and plugins state theirs through
 * {@link SpiceCommandPlugin#configurationPathKeys()}. Nothing is
 * inferred from a value's shape.
 *
 * <p>Printed as part of the path manifest, so one container round-trip answers both
 * questions. That matters more than it looks: the round-trip is ~0.36s and almost all of it
 * is startup, so asking twice would double the cost of an answer one call could carry.
 */
final class ConfigurationPaths {

  /** The marker the wrapper looks for. */
  static final String HEADER = "# spice-config-paths 1";

  private ConfigurationPaths() {}

  /**
   * Path-valued keys of the built-in commands.
   *
   * <p>Empty today: `spice`'s own settings are counts and levels, and the one path it might
   * have taken — `[logging] file` — is refused precisely because the wrapper handles it on
   * the host. Kept as the place such a key would be declared, beside the plugins' equivalent.
   */
  private static final List<String> BUILT_IN_PATH_KEYS = List.of();

  /**
   * The section to append to the manifest.
   *
   * <p>Failure is silent by design. This runs inside a diagnostic command whose output the
   * wrapper parses; a stack trace here would corrupt the manifest and break every run,
   * whereas an absent section only costs the mounts — which the command itself will then
   * report properly, in its own words, when it cannot reach a directory.
   */
  static String render(Path configFile) {
    StringBuilder out = new StringBuilder("\n").append(HEADER).append('\n');
    try {
      Map<String, Object> root = TomlFile.parse(configFile);
      for (String path : resolve(root, pathKeys())) {
        out.append("P ").append(path).append('\n');
      }
    } catch (RuntimeException e) {
      // Leave the section empty; see above.
    }
    return out.toString();
  }

  /** Every declared path key: the built-ins', plus every mounted plugin's. */
  private static Set<String> pathKeys() {
    Set<String> keys = new LinkedHashSet<>(BUILT_IN_PATH_KEYS);
    keys.addAll(PluginLoader.configurationPathKeys());
    return keys;
  }

  /**
   * The values those keys hold, as absolute paths.
   *
   * <p>A key that is absent is skipped, and so is a relative one: the wrapper mounts a path
   * at its own location on the host, which only means anything for an absolute path. A
   * relative path in a config file has no dependable meaning anyway, since the file may be
   * read from a directory the process cannot see.
   */
  private static List<String> resolve(Map<String, Object> root, Set<String> keys) {
    List<String> paths = new ArrayList<>();
    for (String dotted : keys) {
      int dot = dotted.indexOf('.');
      if (dot < 0) {
        continue;
      }
      Object group = root.get(dotted.substring(0, dot));
      if (!(group instanceof Map<?, ?> table)) {
        continue;
      }
      Object value = table.get(dotted.substring(dot + 1));
      if (value instanceof String text && !text.isBlank() && Path.of(text).isAbsolute()) {
        paths.add(text);
      }
    }
    return paths;
  }
}
