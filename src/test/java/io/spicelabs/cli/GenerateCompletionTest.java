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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/**
 * Coverage for the tab-completion generators — that both `generate-completion` (bash/zsh, from
 * the live picocli model) and `generate-powershell-completion` emit a working script listing the
 * standard built-in commands (`survey`, `pass`) and their subcommands. Replaces the tests for the
 * former hand-maintained static completion scripts, which were superseded by dynamic generation.
 */
class GenerateCompletionTest {

  private record Result(int exitCode, String out) {}

  /**
   * Execute a top-level command, capturing everything it writes to stdout. System.out is
   * redirected before the CommandLine is built so it captures both the picocli `out` writer
   * (used by `generate-completion`) and direct System.out.println (used by the PowerShell
   * generator).
   */
  private static Result run(String... args) {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
    System.setOut(capture);
    int exitCode;
    try {
      CommandLine cmd = SpiceLabsCLI.newCommandLine();
      exitCode = cmd.execute(args);
    } finally {
      System.setOut(originalOut);
      capture.flush();
    }
    return new Result(exitCode, buffer.toString(StandardCharsets.UTF_8));
  }

  // ── bash/zsh: picocli generate-completion ──────────────────────────────────

  @Test
  void bashCompletion_generatesSuccessfully() {
    Result r = run("generate-completion");
    assertEquals(0, r.exitCode(), "generate-completion should exit 0");
    assertFalse(r.out().isBlank(), "generate-completion should emit a completion script");
  }

  @Test
  void bashCompletion_includesStandardCommands() {
    String out = run("generate-completion").out();
    assertTrue(out.contains("spice"), "completion script should target the `spice` command");
    assertTrue(out.contains("survey"), "completion should include the built-in `survey` command");
    assertTrue(out.contains("pass"), "completion should include the built-in `pass` command");
  }

  // ── PowerShell: generate-powershell-completion ─────────────────────────────

  @Test
  void powershellCompletion_generatesSuccessfully() {
    Result r = run("generate-powershell-completion");
    assertEquals(0, r.exitCode(), "generate-powershell-completion should exit 0");
    assertTrue(r.out().contains("$SpiceCompletions"),
        "PowerShell completion should emit the $SpiceCompletions table");
    assertTrue(r.out().contains("Register-ArgumentCompleter"),
        "PowerShell completion should register the argument completer");
  }

  @Test
  void powershellCompletion_includesStandardCommandsAndSubcommands() {
    String out = run("generate-powershell-completion").out();
    assertTrue(out.contains("survey"), "should include the `survey` command");
    assertTrue(out.contains("pass"), "should include the `pass` command");
    assertTrue(out.contains("inventory"), "should include the `survey inventory` subcommand");
    assertTrue(out.contains("runtime"), "should include the `survey runtime` subcommand");
    assertTrue(out.contains("decode"), "should include the `pass decode` subcommand");
  }
}
