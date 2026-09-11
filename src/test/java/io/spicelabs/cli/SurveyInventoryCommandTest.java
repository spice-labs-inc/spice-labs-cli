// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.spicelabs.config.Logging;
import io.spicelabs.config.Origin;
import io.spicelabs.config.Resolution;
import io.spicelabs.config.Resolver;
import io.spicelabs.ginger.Ginger;

/**
 * Guards the encrypt-only gate: encrypt-only runs never contact a server, so the command
 * must not try to register a survey (which would fail on the missing/dummy pass); and the
 * artifact cutoff, which decides how much of the estate the survey covers.
 */
class SurveyInventoryCommandTest {

  /**
   * The cutoff constrains what the platform will accept, so it must come from the pass and
   * nowhere else — never from a system property, which {@code -D} would let any caller set,
   * turning a bound the platform imposed into one the caller chooses.
   *
   * <p>This asserts on {@code passCutoff()}, the path that actually reaches GoatRodeo. A test
   * that only checked {@link PassClaims} would pass even if this method went back to consulting
   * a property, since {@code PassClaims} reads the JWT and would never see one.
   */
  @Test
  void noSystemPropertyCanSupplyACutoff() {
    String saved = System.getProperty("spice.cutoff");
    try {
      System.setProperty("spice.cutoff", "2026-01-01T00:00:00Z");

      SurveyInventoryCommand withoutClaim = new SurveyInventoryCommand();
      withoutClaim.spicePassOverride = pass("{\"x-uuid-project\":\"p\"}");
      assertEquals(Optional.empty(), withoutClaim.passCutoff(),
          "a pass with no x-cutoff means no cutoff, whatever the property says");

      SurveyInventoryCommand withClaim = new SurveyInventoryCommand();
      withClaim.spicePassOverride = pass("{\"x-cutoff\":1767225600}");
      assertEquals(Optional.of(Instant.ofEpochSecond(1767225600L)), withClaim.passCutoff(),
          "the cutoff is the pass's, not the property's");
    } finally {
      if (saved == null) {
        System.clearProperty("spice.cutoff");
      } else {
        System.setProperty("spice.cutoff", saved);
      }
    }
  }

  /** An override carries its own cutoff rather than inheriting the ambient pass's. */
  @Test
  void anOverriddenPassCarriesItsOwnCutoff() {
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    cmd.spicePassOverride = pass("{\"x-cutoff\":1767225600}");
    assertEquals(Optional.of(Instant.ofEpochSecond(1767225600L)), cmd.passCutoff());
  }

  private static String pass(String claimsJson) {
    return b64("{\"alg\":\"none\"}") + "." + b64(claimsJson) + ".sig";
  }

  private static String b64(String json) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void isEncryptOnly_falseWhenAbsent() {
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    cmd.gingerArgs = Map.of();
    assertFalse(cmd.isEncryptOnly());
  }

  @Test
  void isEncryptOnly_trueWhenSet() {
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    cmd.gingerArgs = Map.of("--encrypt-only", "true");
    assertTrue(cmd.isEncryptOnly());
  }

  @Test
  void isEncryptOnly_trueWhenBareFlag() {
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    cmd.gingerArgs = Map.of("--encrypt-only", "");
    assertTrue(cmd.isEncryptOnly());
  }

  @Test
  void isEncryptOnly_falseWhenExplicitlyFalse() {
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    cmd.gingerArgs = Map.of("--encrypt-only", "false");
    assertFalse(cmd.isEncryptOnly());
  }

  @Test
  void anUnknownUploadSettingIsAnError() {
    // The group is a closed list applied through the uploader's typed setters. Anything
    // else names itself, rather than being forwarded as a flag the uploader would warn
    // about in a log nobody reads.
    SurveyInventoryCommand command = new SurveyInventoryCommand();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> command.applyUploadSettings(
                Ginger.builder(),
                Resolution.of(
                    Map.of("upload", Map.of("chunk_size_mb", 64L)),
                    Origin.defaultValue())));

    assertTrue(thrown.getMessage().contains("chunk_size_mb"), thrown.getMessage());
  }

  @Test
  void theSpicePassCannotBeSetFromConfiguration() {
    // The uploader applies extraArgs inside run(), where they assign its jwt and uuid
    // fields — so anything reaching extraArgs overrides the credential the platform
    // issued. `jwt` is not a setting, and saying so is the check that keeps it that way.
    SurveyInventoryCommand command = new SurveyInventoryCommand();
    for (String credential : java.util.List.of("jwt", "uuid")) {
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> command.applyUploadSettings(
                  Ginger.builder(),
                  Resolution.of(
                      Map.of("upload", Map.of(credential, "forged")),
                      Origin.defaultValue())),
              credential + " must not be settable in [upload]");
      assertTrue(thrown.getMessage().contains(credential), thrown.getMessage());
    }
  }

  @Test
  void settingsAreResolvedOncePerRun() {
    // The resolver reports every override as it decides it. Resolving on each step of the
    // run said the same thing once per step; the survey, the upload and the encrypt-only
    // gate must all read the one resolution.
    SurveyInventoryCommand command = new SurveyInventoryCommand();
    assertSame(command.settings(), command.settings());
  }

  @Test
  void explainShowsADefaultNobodySet() {
    // "Not shown" is a poor way to say "8": a setting the run will use must appear in
    // `spice config explain` even when nothing in the file, environment or flags touched it.
    String explained =
        RunConfiguration.EMPTY
            .explain(
                SurveyInventoryCommand.COMMAND_PATH,
                SurveyInventoryCommand.GROUPS,
                SurveyInventoryCommand.defaults())
            .explain();
    for (String key : List.of("threads", "max_records", "level")) {
      assertTrue(explained.contains(key), key + " missing from:\n" + explained);
    }
    assertTrue(explained.contains("default"), explained);
  }

  @Test
  void theUploaderKeepsItsOwnChunkSizeDefault() {
    // No default for target_chunk_size here: one would reach the uploader's setter on every
    // run and its own default could never apply. With nothing set, the key is simply absent.
    SurveyInventoryCommand command = new SurveyInventoryCommand();
    assertTrue(command.resolveSettings().setting("upload", "target_chunk_size").isEmpty());
  }

  @Test
  void aChunkSizeThatIsNotAPositiveIntIsRefusedByName() {
    SurveyInventoryCommand command = new SurveyInventoryCommand();
    for (long bad : new long[] {0L, -1L, 1L + Integer.MAX_VALUE}) {
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> command.applyUploadSettings(
                  Ginger.builder(),
                  Resolution.of(
                      Map.of("upload", Map.of("target_chunk_size", bad)),
                      Origin.defaultValue())),
              "chunk size " + bad + " must be refused");
      assertTrue(thrown.getMessage().contains("target_chunk_size"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains(Long.toString(bad)), thrown.getMessage());
    }
  }

  @Test
  void aResolvedLoggingLevelReachesThisProgramsLogger() {
    // `--log-level` is a binding onto [logging] level, so a level from the file or the
    // environment must move spice's own logger too — not just the analysis engine's.
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ch.qos.logback.classic.Level saved = root.getLevel();
    String savedScala = System.getProperty("scala.logging.level");
    try {
      SurveyInventoryCommand command = new SurveyInventoryCommand();
      command.logLevel = null;
      command.settings =
          Resolution.of(
              Map.of("logging", Map.of("level", "debug")),
              Origin.sharedTable(java.nio.file.Path.of("spice.toml"), "logging"));
      command.configureLogging();
      assertEquals(ch.qos.logback.classic.Level.DEBUG, root.getLevel());
      assertEquals("DEBUG", System.getProperty("scala.logging.level"));
    } finally {
      root.setLevel(saved);
      if (savedScala == null) {
        System.clearProperty("scala.logging.level");
      } else {
        System.setProperty("scala.logging.level", savedScala);
      }
    }
  }

  @Test
  void aLogFileInAConfigFileIsRefused(@TempDir Path dir) throws Exception {
    // The wrapper mounts what it can see on the command line and does not parse TOML, so a
    // path written here would be written inside the container and lost. `--log-file` works.
    Path config =
        Files.writeString(dir.resolve("config.toml"), "[logging]\nfile = \"/tmp/spice.log\"\n");
    RunConfiguration.load(config);
    try {
      SurveyInventoryCommand command = new SurveyInventoryCommand();

      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, command::configureLogging);

      assertTrue(thrown.getMessage().contains("--log-file"), thrown.getMessage());
      assertTrue(thrown.getMessage().contains("[logging]"), thrown.getMessage());
    } finally {
      RunConfiguration.load(null);
    }
  }

  @Test
  void aLogFileInACommandScopedTableIsRefused(@TempDir Path dir) throws Exception {
    // The command-scoped table is as invisible to the wrapper as the shared one, so a check
    // that only read `[logging]` would let this straight through.
    Path config =
        Files.writeString(
            dir.resolve("config.toml"),
            "[survey.inventory.logging]\nfile = \"/tmp/spice.log\"\n");
    RunConfiguration.load(config);
    try {
      SurveyInventoryCommand command = new SurveyInventoryCommand();

      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, command::configureLogging);

      assertTrue(
          thrown.getMessage().contains("[survey.inventory.logging]"), thrown.getMessage());
    } finally {
      RunConfiguration.load(null);
    }
  }

  @Test
  void aLogFileFromTheEnvironmentIsRefused() {
    // A third name for the same setting, and the wrapper cannot see through it either. The
    // resolver is built here rather than through `RunConfiguration`, which reads the real
    // environment.
    Resolution settings =
        new Resolver("SPICE", Set.of(Logging.GROUP), message -> {})
            .withEnvironment(Map.of("SPICE_LOGGING_FILE", "/tmp/spice.log"))
            .resolve();

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SurveyInventoryCommand().rejectUnmountableLogFile(settings));

    assertTrue(thrown.getMessage().contains("SPICE_LOGGING_FILE"), thrown.getMessage());
  }

  @Test
  void theFlagWinsOverAConfiguredLogFileRatherThanFailing(@TempDir Path dir) throws Exception {
    // Precedence says the flag wins, so the path in hand is one the wrapper can mount and the
    // run is well formed. Refusing on the mere presence of the configured value would fail it.
    Path config =
        Files.writeString(dir.resolve("config.toml"), "[logging]\nfile = \"/tmp/inside.log\"\n");
    RunConfiguration.load(config);
    try {
      SurveyInventoryCommand command = new SurveyInventoryCommand();
      command.logFile = "/host/spice.log";

      Resolution settings = command.resolveSettings();
      command.rejectUnmountableLogFile(settings);

      assertEquals("/host/spice.log", Logging.file(settings).orElseThrow());
    } finally {
      RunConfiguration.load(null);
    }
  }

  @Test
  void theLogFileFlagBindsOntoTheLoggingGroup() {
    // It was declared and never applied: the description promised "output appended to both
    // console and file" and nothing wrote one.
    SurveyInventoryCommand command = new SurveyInventoryCommand();
    command.logFile = "/tmp/spice-test.log";
    command.logLevel = "debug";

    Resolution settings = command.resolveSettings();

    assertEquals("/tmp/spice-test.log", Logging.file(settings).orElseThrow());
    assertEquals("DEBUG", Logging.level(settings));
  }

  @Test
  void theLoggingGroupSuppliesTheLevelWhenNoFlagDoes() {
    // So `[logging] level` and SPICE_LOGGING_LEVEL work, not only the flag.
    SurveyInventoryCommand command = new SurveyInventoryCommand();

    assertEquals("INFO", Logging.level(command.resolveSettings()));
  }
}
