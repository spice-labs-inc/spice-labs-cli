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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code spice docs} with a user guide in the build, and without one; DocsCommandTest covers
 * the command model itself. The guide here is a test fixture (src/test/resources/docs-test-guide),
 * so the other tests keep seeing a build without one. These tests' output is not a terminal,
 * so nothing opens a browser.
 */
class DocsGuideTest {

  private static final String FIXTURE = "/docs-test-guide/";

  @AfterEach
  void noGuide() {
    Guide.useRoot(null);
  }

  private record Run(int exit, String out) {}

  private static Run run(String... args) {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      int exit = SpiceLabsCLI.newCommandLine().execute(args);
      return new Run(exit, buffer.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
    }
  }

  private static String fixture(String name) throws IOException {
    try (var in = DocsGuideTest.class.getResourceAsStream(FIXTURE + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // ── The command model is the same whether or not there is a guide ──────────

  @Test
  void jsonIsTheSameWithAndWithoutAGuide() {
    Run without = run("docs", "--json");
    Guide.useRoot(FIXTURE);
    Run with = run("docs", "--json");
    assertEquals(0, without.exit());
    assertEquals(0, with.exit());
    assertEquals(without.out(), with.out());
  }

  @Test
  void withoutAGuideDocsStillPrintsTheManual() {
    Run plain = run("docs");
    assertEquals(0, plain.exit());
    assertTrue(plain.out().contains("Usage: spice survey inventory"), plain.out());
    assertEquals(plain.out(), run("docs", "--commands").out());
  }

  @Test
  void commandsPrintsTheManualWhenThereIsAGuide() {
    String manual = run("docs").out();
    Guide.useRoot(FIXTURE);
    Run commands = run("docs", "--commands");
    assertEquals(0, commands.exit());
    assertEquals(manual, commands.out());
  }

  // ── With a guide ─────────────────────────────────────────────────────────────

  @Test
  void withAGuideDocsPrintsItAsMarkdownWhenOutputIsNotATerminal() throws IOException {
    Guide.useRoot(FIXTURE);
    Run docs = run("docs");
    assertEquals(0, docs.exit());
    assertEquals(fixture("guide.md"), docs.out());
  }

  @Test
  void aPageOnItsOwn() throws IOException {
    Guide.useRoot(FIXTURE);
    assertEquals(fixture("pages/setup.md"), run("docs", "setup").out());
    assertEquals(fixture("pages/intro.md"), run("docs", "--markdown", "intro").out());
  }

  @Test
  void anUnknownPageIsAUsageError() {
    Guide.useRoot(FIXTURE);
    Run docs = run("docs", "nope");
    assertEquals(2, docs.exit());
    assertTrue(docs.out().contains("There is no page 'nope'. The pages are: intro, setup."), docs.out());
  }

  @Test
  void htmlOpensAtTheChosenPage() {
    Guide.useRoot(FIXTURE);
    String whole = run("docs", "--html").out();
    assertTrue(whole.startsWith("<!DOCTYPE html>"), whole);
    assertFalse(whole.contains("location.replace"), whole);
    String setup = run("docs", "--html", "setup").out();
    assertTrue(setup.contains("<script>if(!location.hash)location.replace('#setup')</script></head>"), setup);
  }

  @Test
  void oneFormAtATime() {
    Guide.useRoot(FIXTURE);
    assertEquals(2, run("docs", "--json", "--markdown").exit());
    assertEquals(2, run("docs", "--commands", "--html").exit());
    assertEquals(2, run("docs", "--json", "intro").exit());
  }

  // ── Without a guide, asking for one is an error ──────────────────────────────

  @Test
  void withoutAGuideItsFormsAndPagesFail() {
    assertEquals(1, run("docs", "intro").exit());
    assertEquals(1, run("docs", "--markdown").exit());
    assertEquals(1, run("docs", "--html").exit());
  }

  // ── Completion ───────────────────────────────────────────────────────────────

  @Test
  void completionOffersThePages() {
    Guide.useRoot(FIXTURE);
    var candidates = new ArrayList<String>();
    new DocsCommand.PageIds().forEach(candidates::add);
    assertEquals(List.of("intro", "setup"), candidates);
    String ps = GeneratePowershellCompletion.header(Edition.current());
    assertTrue(ps.contains("docs = @{\n    __sub  = @('intro', 'setup', '--json', '--commands', '--browser', '--markdown', '--html', '--help')"), ps);
  }

  // ── Where a browser can be opened ────────────────────────────────────────────

  @Test
  void noBrowserInAContainer() {
    assertInstanceOf(HostBrowser.Unavailable.class,
        HostBrowser.find(Map.of("PATH", "/usr/bin"), "Mac OS X", p -> p.toString().equals("/.dockerenv")));
  }

  @Test
  void noBrowserOverSsh() {
    var found = HostBrowser.find(Map.of("SSH_CONNECTION", "10.0.0.1 22 10.0.0.2 22"), "Mac OS X", p -> false);
    assertTrue(((HostBrowser.Unavailable) found).reason().contains("SSH"));
  }

  @Test
  void macOsNeedsOpen(@TempDir Path bin) throws IOException {
    assertInstanceOf(HostBrowser.Unavailable.class,
        HostBrowser.find(Map.of("PATH", bin.toString()), "Mac OS X", p -> false));
    executable(bin.resolve("open"));
    var found = HostBrowser.find(Map.of("PATH", bin.toString()), "Mac OS X", p -> false);
    assertEquals("open", ((HostBrowser.Found) found).name());
  }

  @Test
  void linuxNeedsADisplayAndXdgOpen(@TempDir Path bin) throws IOException {
    executable(bin.resolve("xdg-open"));
    assertInstanceOf(HostBrowser.Unavailable.class,
        HostBrowser.find(Map.of("PATH", bin.toString()), "Linux", p -> false));
    var found = HostBrowser.find(Map.of("PATH", bin.toString(), "DISPLAY", ":0"), "Linux", p -> false);
    assertEquals("xdg-open", ((HostBrowser.Found) found).name());
  }

  @Test
  void windowsUsesStart() {
    var found = HostBrowser.find(Map.of(), "Windows 11", p -> false);
    assertEquals("start", ((HostBrowser.Found) found).name());
  }

  private static void executable(Path file) throws IOException {
    Files.writeString(file, "#!/bin/sh\nexit 0\n");
    try {
      Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    } catch (UnsupportedOperationException e) {
      file.toFile().setExecutable(true);
    }
  }
}
