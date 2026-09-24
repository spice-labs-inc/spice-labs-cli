// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors

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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

/**
 * Refuses to run a command the edition is not licensed for.
 *
 * <p>Installed as the command line's execution strategy, so it sees the command picocli has
 * chosen and nothing else has run yet. It answers help and version requests first — picocli
 * does that inside its default strategy, and a subcommand's {@code --help} is not visible on
 * the top-level parse result, so the dispatch must happen here rather than before — then lets
 * through the commands on the {@link #EXEMPT} list, and only then asks {@link License}.
 *
 * <p>The list is a <em>default-deny</em> allowlist of commands that do no licensed work:
 *
 * <ul>
 *   <li>the bare parents ({@code spice}, {@code spice survey}, {@code spice pass},
 *       {@code spice config}), which only print usage;</li>
 *   <li>{@code pass decode} — a customer with a bad license must be able to see what they hold;</li>
 *   <li>{@code config} and {@code config explain}, which only report configuration;</li>
 *   <li>{@code path-manifest}, {@code generate-completion} and
 *       {@code generate-powershell-completion}, which the host wrapper and the installer run on
 *       the user's behalf, in a container that has no {@code SPICE_PASS} at all. Gating them
 *       would break installation and bind-mounting before the user ever saw a license message.</li>
 * </ul>
 *
 * Everything else — every survey, every plugin command — is gated. A plugin mounted into this
 * tree is constructed before the gate runs (it happens while the tree is being built), which is
 * safe because mounting only records the context and builds the picocli model; no plugin does
 * work until its command executes, and that is what this refuses.
 */
final class LicenseGate {

  private static final Logger log = LoggerFactory.getLogger(LicenseGate.class);

  /** Exit code for a refused run: a failure, but not a usage error (picocli's 2). */
  static final int EXIT_CODE = 1;

  /**
   * Command paths, root omitted, that run without a license. An empty path is the bare root.
   * Kept as a set of joined paths so the test can compare it against what the wrapper calls.
   */
  static final Set<String> EXEMPT = Set.of(
      "",
      "survey",
      "pass",
      "pass decode",
      "config",
      "config explain",
      "path-manifest",
      "generate-completion",
      "generate-powershell-completion");

  private LicenseGate() {}

  /** Install the gate on {@code cmd}, checking {@code context}'s credential against {@code edition}. */
  static void apply(CommandLine cmd, Edition edition, DefaultSpiceContext context) {
    cmd.setExecutionStrategy(parseResult -> {
      Integer helped = CommandLine.executeHelpRequest(parseResult);
      if (helped != null) {
        return helped;
      }
      if (!exempt(parseResult)) {
        switch (License.check(edition, context.spicePass(), context.credentialVariable())) {
          case License.Refused refused -> {
            log.error("❌ {}", refused.reason());
            for (String hint : refused.hints()) {
              log.info("   {}", hint);
            }
            return EXIT_CODE;
          }
          case License.Granted granted -> granted.warning().ifPresent(log::warn);
        }
      }
      return new CommandLine.RunLast().execute(parseResult);
    });
  }

  /** Whether the command picocli chose is on the list. */
  static boolean exempt(ParseResult parseResult) {
    return EXEMPT.contains(String.join(" ", path(parseResult)));
  }

  /** The chosen command's path below the root, e.g. {@code [pass, decode]}. */
  static List<String> path(ParseResult parseResult) {
    List<String> path = new ArrayList<>();
    ParseResult level = parseResult;
    while (level.hasSubcommand()) {
      level = level.subcommand();
      path.add(level.commandSpec().name());
    }
    return path;
  }
}
