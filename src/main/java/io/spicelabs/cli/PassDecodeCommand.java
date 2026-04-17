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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine.Command;

/**
 * Decodes and displays SPICE_PASS JWT information.
 * Usage: spice pass decode
 */
@Command(
    name = "decode",
    description = "Decode and display Spice Pass (JWT) information",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Example:",
        "  SPICE_PASS=$MY_TOKEN spice pass decode",
        "",
        "SPICE_PASS must be set in the environment.",
        ""
    }
)
public class PassDecodeCommand implements java.util.concurrent.Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(PassDecodeCommand.class);

  // For testing
  String spicePassOverride;

  @Override
  public Integer call() throws Exception {
    try {
      String spicePass = resolveSpicePass();
      if (spicePass == null || spicePass.isBlank()) {
        log.error("❌ SPICE_PASS must be set via SPICE_PASS env var");
        return 1;
      }
      SpicePassDecoder decoder = new SpicePassDecoder(spicePass);
      decoder.printFullInfo();
      return 0;
    } catch (Exception ex) {
      log.error("❌ {}", ex.getMessage());
      return 1;
    }
  }

  private String resolveSpicePass() {
    if (spicePassOverride != null && !spicePassOverride.isBlank()) {
      return spicePassOverride;
    }
    return System.getenv("SPICE_PASS");
  }
}
