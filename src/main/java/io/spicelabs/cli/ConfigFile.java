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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the configuration file for a run.
 *
 * <p>Named with {@code --config <file>} or {@code --config=<file>}, or discovered in the
 * platform's standard configuration directory. The first file found wins; the rest are not
 * consulted. First-wins rather than merging keeps "where did this value come from" a question
 * with one answer.
 *
 * <p><strong>Unix</strong> follows the XDG base directory specification, which the {@code
 * spice} wrapper already uses for its manifest cache:
 * <ol>
 *   <li>{@code $XDG_CONFIG_HOME/spice/config.toml} (default {@code $HOME/.config})</li>
 *   <li>{@code <dir>/spice/config.toml} for each {@code $XDG_CONFIG_DIRS} entry in order
 *       (default {@code /etc/xdg})</li>
 * </ol>
 *
 * <p><strong>Windows</strong> uses the native locations, not XDG:
 * <ol>
 *   <li>{@code %APPDATA%\spice\config.toml} — per-user, roaming</li>
 *   <li>{@code %PROGRAMDATA%\spice\config.toml} — machine-wide</li>
 * </ol>
 * {@code XDG_CONFIG_HOME} is deliberately not consulted there: it would be one more place to
 * look when a value surprises someone, on a platform where nothing else sets it. Roaming
 * {@code %APPDATA%} rather than {@code %LOCALAPPDATA%} because configuration is user intent
 * and should follow the user between machines, unlike the caches the wrapper keeps.
 *
 * <p><strong>Under Docker this finds nothing, by design.</strong> The wrapper runs on the host
 * and resolves the file there, passing it in as {@code --config} so it is bind-mounted like
 * any other path argument. The container gets neither {@code HOME} nor {@code XDG_*}, so
 * discovery inside it would read the *container's* home rather than the user's. Finding
 * nothing is the correct outcome and needs no container detection. This class exists for
 * {@code java -jar} runs that bypass the wrapper.
 */
final class ConfigFile {

  /** The file name looked for inside each configuration directory. */
  static final String FILE_NAME = "config.toml";

  /** The per-application directory inside each configuration directory. */
  static final String APP_DIR = "spice";

  private ConfigFile() {}

  /**
   * The configuration file for this run.
   *
   * @param explicit the value of {@code --config}, or null if it was not given
   * @return the file to read, or empty when none was given and none was found
   * @throws IllegalArgumentException if {@code --config} names a file that does not exist —
   *     naming a file explicitly and having it silently ignored is never what was meant,
   *     whereas a missing file at a discovered location just means "no config file"
   */
  static Optional<Path> resolve(Path explicit) {
    if (explicit != null) {
      if (!Files.isRegularFile(explicit)) {
        throw new IllegalArgumentException("Config file not found: " + explicit);
      }
      return Optional.of(explicit);
    }
    return discover();
  }

  /** The first configuration file present in the platform's standard locations. */
  static Optional<Path> discover() {
    return searchPath().stream().filter(Files::isRegularFile).findFirst();
  }

  /** The locations searched, in order. Exposed for tests and for diagnostics. */
  static List<Path> searchPath() {
    return isWindows() ? windowsSearchPath() : xdgSearchPath();
  }

  private static List<Path> xdgSearchPath() {
    List<Path> paths = new ArrayList<>();
    String configHome = trimmed(System.getenv("XDG_CONFIG_HOME"));
    if (configHome != null) {
      paths.add(Path.of(configHome, APP_DIR, FILE_NAME));
    } else {
      String home = trimmed(System.getenv("HOME"));
      if (home != null) {
        paths.add(Path.of(home, ".config", APP_DIR, FILE_NAME));
      }
    }

    String configDirs = trimmed(System.getenv("XDG_CONFIG_DIRS"));
    for (String dir : (configDirs != null ? configDirs : "/etc/xdg").split(":")) {
      String trimmedDir = trimmed(dir);
      if (trimmedDir != null) {
        paths.add(Path.of(trimmedDir, APP_DIR, FILE_NAME));
      }
    }
    return paths;
  }

  private static List<Path> windowsSearchPath() {
    List<Path> paths = new ArrayList<>();
    String appData = trimmed(System.getenv("APPDATA"));
    if (appData != null) {
      paths.add(Path.of(appData, APP_DIR, FILE_NAME));
    }
    String programData = trimmed(System.getenv("PROGRAMDATA"));
    if (programData != null) {
      paths.add(Path.of(programData, APP_DIR, FILE_NAME));
    }
    return paths;
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name");
    return os != null && os.toLowerCase().startsWith("windows");
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String result = value.trim();
    return result.isEmpty() ? null : result;
  }
}
