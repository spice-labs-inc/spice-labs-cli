// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.spicelabs.ginger.Ginger;

/**
 * Guards the encrypt-only gate: encrypt-only runs never contact a server, so the command
 * must not try to register a survey (which would fail on the missing/dummy pass).
 */
class SurveyInventoryCommandTest {

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
                io.spicelabs.config.Resolution.of(
                    Map.of("upload", Map.of("chunk_size_mb", 64L)),
                    io.spicelabs.config.Origin.defaultValue())));

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
                  io.spicelabs.config.Resolution.of(
                      Map.of("upload", Map.of(credential, "forged")),
                      io.spicelabs.config.Origin.defaultValue())),
              credential + " must not be settable in [upload]");
      assertTrue(thrown.getMessage().contains(credential), thrown.getMessage());
    }
  }

  @Test
  void aLogFileInAConfigFileIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
      throws Exception {
    // The wrapper mounts what it can see on the command line and does not parse TOML, so a
    // path written here would be written inside the container and lost. `--log-file` works.
    java.nio.file.Path config =
        java.nio.file.Files.writeString(
            dir.resolve("config.toml"), "[logging]\nfile = \"/tmp/spice.log\"\n");
    RunConfiguration.load(config);
    try {
      SurveyInventoryCommand command = new SurveyInventoryCommand();

      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, command::configureLogging);

      assertTrue(thrown.getMessage().contains("--log-file"), thrown.getMessage());
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

    io.spicelabs.config.Resolution settings = command.resolveSettings();

    assertEquals(
        "/tmp/spice-test.log",
        io.spicelabs.config.Logging.file(settings).orElseThrow());
    assertEquals("DEBUG", io.spicelabs.config.Logging.level(settings));
  }

  @Test
  void theLoggingGroupSuppliesTheLevelWhenNoFlagDoes() {
    // So `[logging] level` and SPICE_LOGGING_LEVEL work, not only the flag.
    SurveyInventoryCommand command = new SurveyInventoryCommand();

    assertEquals("INFO", io.spicelabs.config.Logging.level(command.resolveSettings()));
  }
}
