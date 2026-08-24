// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

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
}
