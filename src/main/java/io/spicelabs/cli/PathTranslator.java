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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates container-internal paths back to host paths in error messages.
 *
 * <p>The wrapper script ({@code spice} / {@code spice.ps1}) mounts host
 * directories at fixed container paths ({@code /mnt/input}, {@code /mnt/output},
 * {@code /mnt/config}, {@code /mnt/discovery}) and rewrites the args
 * accordingly. When the CLI inside the container reports an error, the paths
 * in the message are container paths — confusing for the user, who typed host
 * paths.
 *
 * <p>The wrapper passes a path mapping via the {@code SPICE_PATH_MAP}
 * environment variable as {@code container_path:host_path} pairs separated by
 * newlines, e.g.:
 * <pre>
 * /mnt/input:/home/user/myapp.jar
 * /mnt/output:/home/user/out
 * /mnt/config:/home/user/.config/allspice
 * </pre>
 *
 * <p>This class replaces container paths in error messages with the
 * corresponding host paths. If {@code SPICE_PATH_MAP} is unset (e.g. running
 * the CLI directly without the wrapper), the message is returned unchanged.
 */
final class PathTranslator {

  private PathTranslator() {}

  private static final String ENV_PATH_MAP = "SPICE_PATH_MAP";
  private static final String SEPARATOR = ":";

  /** Cached mapping, loaded once on first use. */
  private static volatile Map<String, String> pathMap;

  /**
   * If {@code SPICE_PATH_MAP} is set, translate any container paths in
   * {@code message} back to the host paths the user originally typed.
   * Otherwise return the message unchanged.
   */
  static String translate(String message) {
    Map<String, String> map = getPathMap();
    if (map == null || map.isEmpty()) {
      return message;
    }

    String result = message;
    // Replace longer paths first so /mnt/input/foo doesn't get partially
    // matched by /mnt/input before /mnt/input/foo is tried.
    var entries = map.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
        .toList();
    for (var entry : entries) {
      result = result.replace(entry.getKey(), entry.getValue());
    }
    return result;
  }

  /**
   * Loads the path map from the environment variable. Returns {@code null} if
   * unset or empty.
   */
  private static Map<String, String> getPathMap() {
    if (pathMap != null) {
      return pathMap;
    }
    String raw = System.getenv(ENV_PATH_MAP);
    if (raw == null || raw.isBlank()) {
      pathMap = null;
      return null;
    }
    Map<String, String> map = new LinkedHashMap<>();
    for (String line : raw.split("\n")) {
      int sep = line.indexOf(SEPARATOR);
      if (sep > 0 && sep < line.length() - 1) {
        String container = line.substring(0, sep).trim();
        String host = line.substring(sep + SEPARATOR.length()).trim();
        if (!container.isEmpty() && !host.isEmpty()) {
          map.put(container, host);
        }
      }
    }
    pathMap = map.isEmpty() ? null : map;
    return pathMap;
  }

  /** Test hook: reset the cached map (forces re-read of env var). */
  static void reset() {
    pathMap = null;
  }
}
