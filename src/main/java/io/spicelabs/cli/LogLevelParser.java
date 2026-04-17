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

import ch.qos.logback.classic.Level;

final class LogLevelParser {
  private LogLevelParser() {}

  static Level parse(String raw) {
    if (raw == null) return Level.INFO;
    switch (raw.toUpperCase()) {
      case "DEBUG": return Level.DEBUG;
      case "INFO":  return Level.INFO;
      case "WARN":  return Level.WARN;
      case "ERROR": return Level.ERROR;
      default:
        throw new IllegalArgumentException(
            "Invalid log level '" + raw + "'. Valid values: debug|info|warn|error");
    }
  }
}
