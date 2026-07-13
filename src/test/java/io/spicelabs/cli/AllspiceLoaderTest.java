// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

/**
 * Tests for {@link AllspiceLoader} and the dynamic registration of the
 * {@code spice registry} subcommand.
 *
 * <p>The loader probes for {@code /opt/allspice/allspice.jar} by default,
 * overridable via the {@code ALLSPICE_JAR} env var. These tests use
 * {@link AllspiceLoader#setTestJarPath(String)} to point at a temp JAR
 * (or no JAR at all) to exercise both the present and absent code paths.
 */
class AllspiceLoaderTest {

  @AfterEach
  void cleanup() {
    AllspiceLoader.reset();
  }

  /**
   * When no allspice JAR is present (the OSS image default), isAvailable()
   * returns false and getInstance() returns null.
   */
  @Test
  void jarAbsent_isAvailable_false(@TempDir Path tmp) {
    AllspiceLoader.setTestJarPath(tmp.resolve("nonexistent.jar").toString());
    assertFalse(AllspiceLoader.isAvailable());
    assertNull(AllspiceLoader.getInstance());
  }

  /**
   * When a JAR file exists at the probed path, isAvailable() returns true.
   * getInstance() attempts to load it; with a non-allspice JAR it fails
   * gracefully and returns null (the class isn't found).
   */
  @Test
  void jarExists_isAvailable_true(@TempDir Path tmp) throws Exception {
    Path jar = tmp.resolve("dummy.jar");
    createEmptyJar(jar);
    AllspiceLoader.setTestJarPath(jar.toString());
    assertTrue(AllspiceLoader.isAvailable());
    // Loading a JAR without the AllspiceBuilder class returns null
    assertNull(AllspiceLoader.getInstance());
  }

  /**
   * The registry subcommand is NOT registered when allspice is absent
   * (OSS build behavior).
   */
  @Test
  void registryNotRegistered_whenAllspiceAbsent(@TempDir Path tmp) {
    AllspiceLoader.setTestJarPath(tmp.resolve("nonexistent.jar").toString());
    CommandLine cmd = SpiceLabsCLI.newCommandLine();
    assertNull(cmd.getSubcommands().get("registry"),
        "registry should not be registered when allspice JAR is absent");
  }

  /**
   * The registry subcommand IS registered when a JAR file exists at the
   * probed path (even if it's a dummy — registration only checks file
   * existence, not class loading).
   */
  @Test
  void registryRegistered_whenJarExists(@TempDir Path tmp) throws Exception {
    Path jar = tmp.resolve("dummy.jar");
    createEmptyJar(jar);
    AllspiceLoader.setTestJarPath(jar.toString());
    CommandLine cmd = SpiceLabsCLI.newCommandLine();
    assertNotNull(cmd.getSubcommands().get("registry"),
        "registry should be registered when allspice JAR exists");
  }

  /**
   * The `survey static` subcommand is NOT registered when allspice is absent
   * (OSS build behavior).
   */
  @Test
  void staticNotRegistered_whenAllspiceAbsent(@TempDir Path tmp) {
    AllspiceLoader.setTestJarPath(tmp.resolve("nonexistent.jar").toString());
    CommandLine cmd = SpiceLabsCLI.newCommandLine();
    CommandLine survey = cmd.getSubcommands().get("survey");
    assertNotNull(survey, "survey should always be registered");
    assertNull(survey.getSubcommands().get("static"),
        "survey static should not be registered when allspice JAR is absent");
  }

  /**
   * The `survey static` subcommand IS registered when the allspice JAR exists.
   */
  @Test
  void staticRegistered_whenJarExists(@TempDir Path tmp) throws Exception {
    Path jar = tmp.resolve("dummy.jar");
    createEmptyJar(jar);
    AllspiceLoader.setTestJarPath(jar.toString());
    CommandLine cmd = SpiceLabsCLI.newCommandLine();
    CommandLine survey = cmd.getSubcommands().get("survey");
    assertNotNull(survey, "survey should always be registered");
    assertNotNull(survey.getSubcommands().get("static"),
        "survey static should be registered when allspice JAR exists");
  }

  /**
   * report_cli is not available when the binary is absent (enterprise image).
   */
  @Test
  void reportCliAbsent_whenNotPresent(@TempDir Path tmp) {
    AllspiceLoader.setTestJarPath(tmp.resolve("nonexistent.jar").toString());
    AllspiceLoader.setTestReportCliPath(tmp.resolve("nonexistent-report_cli").toString());
    assertFalse(AllspiceLoader.isReportCliAvailable());
  }

  /**
   * report_cli is available when the binary exists (federal image).
   */
  @Test
  void reportCliAvailable_whenPresent(@TempDir Path tmp) throws Exception {
    Path reportCli = tmp.resolve("report_cli");
    Files.write(reportCli, "dummy binary".getBytes());
    AllspiceLoader.setTestReportCliPath(reportCli.toString());
    assertTrue(AllspiceLoader.isReportCliAvailable());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static void createEmptyJar(Path jarPath) throws Exception {
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
      jos.putNextEntry(new JarEntry("dummy.txt"));
      jos.write("dummy".getBytes());
      jos.closeEntry();
    }
  }
}
