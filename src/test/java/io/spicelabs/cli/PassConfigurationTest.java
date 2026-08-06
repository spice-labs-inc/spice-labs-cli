// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.spicelabs.cli.spi.SpiceConfiguration;

class PassConfigurationTest {

  @Test
  void readsClaimsFromThePass() {
    SpiceConfiguration config = PassConfiguration.of(pass(
        "{\"x-cutoff\":1767225600,"
            + "\"x-upload-server\":\"https://host/upload\","
            + "\"x-uuid-project\":\"proj\","
            + "\"x-uuid-org\":\"org\","
            + "\"x-uuid-user\":\"user\"}"));

    assertEquals(Optional.of(Instant.ofEpochSecond(1767225600L)), config.cutoff());
    assertEquals(Optional.of("https://host/upload"), config.uploadServer());
    assertEquals(Optional.of("proj"), config.projectId());
    assertEquals(Optional.of("org"), config.organizationId());
    assertEquals(Optional.of("user"), config.userId());
  }

  @Test
  void absentClaimsAreEmptyRatherThanDefaulted() {
    SpiceConfiguration config = PassConfiguration.of(pass("{\"x-uuid-project\":\"proj\"}"));
    assertTrue(config.cutoff().isEmpty(), "no x-cutoff means no cutoff, not the epoch");
    assertTrue(config.uploadServer().isEmpty());
    assertEquals(Optional.of("proj"), config.projectId());
  }

  @Test
  void noPassYieldsTheEmptyConfiguration() {
    assertSame(SpiceConfiguration.EMPTY, PassConfiguration.of(null));
    assertSame(SpiceConfiguration.EMPTY, PassConfiguration.of("  "));
  }

  @Test
  void anUndecodablePassIsEmptyRatherThanFatal() {
    // Configuration is a convenience: a broken pass must not stop a command from starting.
    // Commands that genuinely need the pass report that themselves.
    SpiceConfiguration config = PassConfiguration.of("not-a-jwt");
    assertSame(SpiceConfiguration.EMPTY, config);
  }

  /**
   * The cutoff constrains what the platform will accept, so it must come from the pass and
   * nowhere else. It used to be republished as {@code -Dspice.cutoff} for in-process plugins,
   * which meant anyone could set it on the command line. This guards against that returning.
   */
  @Test
  void noSystemPropertyCanSupplyACutoff() {
    String saved = System.getProperty("spice.cutoff");
    try {
      System.setProperty("spice.cutoff", "2026-01-01T00:00:00Z");
      assertTrue(PassConfiguration.of(null).cutoff().isEmpty());
      assertTrue(PassConfiguration.of(pass("{\"x-uuid-project\":\"p\"}")).cutoff().isEmpty());
      assertFalse(SpiceConfiguration.EMPTY.cutoff().isPresent());
    } finally {
      if (saved == null) {
        System.clearProperty("spice.cutoff");
      } else {
        System.setProperty("spice.cutoff", saved);
      }
    }
  }

  private static String pass(String claimsJson) {
    return b64("{\"alg\":\"none\"}") + "." + b64(claimsJson) + ".sig";
  }

  private static String b64(String json) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
