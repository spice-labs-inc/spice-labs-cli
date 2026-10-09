// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code User-Agent} every request to the platform carries: the product and its version,
 * then a comment naming the edition (when the build has one) and the command that ran, e.g.
 * {@code spice-labs-cli/1.8.5 (edition it; command survey inventory)}. The server counts
 * command use from it; an older server reads only the leading product token.
 */
final class UserAgent {

  static final String PRODUCT = "spice-labs-cli";

  private static volatile String command = "";

  private UserAgent() {}

  /** Record the command picocli chose, as its path below the root, e.g. {@code [survey, inventory]}. */
  static void command(List<String> path) {
    command = String.join(" ", path);
  }

  static String value() {
    return value(SpiceLabsCLI.VersionProvider.getVersionString(), Edition.current(), command);
  }

  static String value(String version, Edition edition, String command) {
    List<String> details = new ArrayList<>();
    if (edition.branded()) {
      details.add("edition " + edition.id());
    }
    if (!command.isEmpty()) {
      details.add("command " + command);
    }
    String product = PRODUCT + "/" + version;
    return details.isEmpty() ? product : product + " (" + String.join("; ", details) + ")";
  }
}
