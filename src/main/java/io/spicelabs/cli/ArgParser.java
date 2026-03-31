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
import java.util.List;
import java.util.Map;

/**
 * Shared argument parsing utilities.
 */
public class ArgParser {

  /**
   * Parse a List of args into a Map.
   * Supports:
   *   key=value
   *   key value   (paired tokens)
   *   key         (flag → "true")
   */
  public static Map<String, String> parseKeyValueList(List<String> raw) {
    Map<String, String> map = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) return map;

    for (int i = 0; i < raw.size(); i++) {
      String token = raw.get(i);
      if (token == null) continue;

      if (token.contains("=")) {
        String[] kv = token.split("=", 2);
        String k = kv.length > 0 ? kv[0] : token;
        String v = kv.length == 2 ? kv[1] : "";
        map.put(k, v);
      } else {
        String value = "true";
        if (i + 1 < raw.size()) {
          String next = raw.get(i + 1);
          if (next != null && !next.startsWith("-") && !next.contains("=")) {
            value = next;
            i++;
          }
        }
        map.put(token, value);
      }
    }
    return map;
  }
}
