// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.spicelabs.cli.spi.SpicePassClaims;

class PassClaimsTest {

  @Test
  void registeredClaimsComeOutTyped() {
    SpicePassClaims claims = PassClaims.of(pass(
        "{\"iss\":\"spice-labs\","
            + "\"sub\":\"user@example.com\","
            + "\"aud\":\"platform\","
            + "\"exp\":1767225600,"
            + "\"nbf\":1704067200,"
            + "\"iat\":1704067200,"
            + "\"jti\":\"pass-123\"}"));

    assertEquals(Optional.of("spice-labs"), claims.issuer());
    assertEquals(Optional.of("user@example.com"), claims.subject());
    assertEquals(List.of("platform"), claims.audience());
    assertEquals(Optional.of(Instant.ofEpochSecond(1767225600L)), claims.expiresAt());
    assertEquals(Optional.of(Instant.ofEpochSecond(1704067200L)), claims.notBefore());
    assertEquals(Optional.of(Instant.ofEpochSecond(1704067200L)), claims.issuedAt());
    assertEquals(Optional.of("pass-123"), claims.jwtId());
  }

  @Test
  void additionalClaimsArriveVerbatimWithRegisteredOnesExcluded() {
    SpicePassClaims claims = PassClaims.of(pass(
        "{\"iss\":\"spice-labs\","
            + "\"exp\":1767225600,"
            + "\"x-cutoff\":1767225600,"
            + "\"x-upload-server\":\"https://host/upload\","
            + "\"x-uuid-project\":\"proj\"}"));

    Map<String, Object> additional = claims.additionalClaims();
    // The registered claims are reachable typed; repeating them in the map would give
    // every value two spellings.
    assertFalse(additional.containsKey("iss"));
    assertFalse(additional.containsKey("exp"));
    // The map is a faithful transcript: x-cutoff stays what the pass encodes, an
    // epoch-seconds Long — interpretation belongs to the consumer.
    assertEquals(1767225600L, additional.get("x-cutoff"));
    assertEquals("https://host/upload", additional.get("x-upload-server"));
    assertEquals("proj", additional.get("x-uuid-project"));
  }

  @Test
  void structuredClaimValuesArePlainJavaTypes() {
    // The SPI promises java.* values only, with integral numbers always Long — Jackson
    // hands back Integer for small ones, so normalisation must be recursive.
    SpicePassClaims claims = PassClaims.of(pass(
        "{\"x-scopes\":[\"read\",\"write\"],"
            + "\"x-limits\":{\"max_uploads\":5,\"nested\":{\"depth\":2}}}"));

    assertEquals(List.of("read", "write"), claims.additionalClaims().get("x-scopes"));
    Object limits = claims.additionalClaims().get("x-limits");
    assertTrue(limits instanceof Map, "expected a plain Map, got: " + limits.getClass());
    Map<?, ?> limitsMap = (Map<?, ?>) limits;
    assertEquals(5L, limitsMap.get("max_uploads"));
    assertEquals(2L, ((Map<?, ?>) limitsMap.get("nested")).get("depth"));
  }

  @Test
  void absentClaimsAreEmptyRatherThanDefaulted() {
    SpicePassClaims claims = PassClaims.of(pass("{\"x-uuid-project\":\"proj\"}"));
    assertTrue(claims.expiresAt().isEmpty());
    assertTrue(claims.issuer().isEmpty());
    assertEquals(List.of(), claims.audience());
    assertFalse(
        claims.additionalClaims().containsKey("x-cutoff"),
        "no x-cutoff claim means no cutoff, not a default");
  }

  @Test
  void noPassYieldsTheEmptyClaims() {
    assertSame(SpicePassClaims.EMPTY, PassClaims.of(null));
    assertSame(SpicePassClaims.EMPTY, PassClaims.of("  "));
  }

  @Test
  void anUndecodablePassIsEmptyRatherThanFatal() {
    // Claims are a convenience: a broken pass must not stop a command from starting.
    // Commands that genuinely need the pass report that themselves.
    assertSame(SpicePassClaims.EMPTY, PassClaims.of("not-a-jwt"));
  }

  /**
   * The cutoff constrains what the platform will accept, so it must come from the pass and
   * nowhere else. It used to be republished as {@code -Dspice.cutoff} for in-process
   * plugins, which meant anyone could set it on the command line. This guards against that
   * returning.
   */
  @Test
  void noSystemPropertyCanSupplyACutoff() {
    String saved = System.getProperty("spice.cutoff");
    try {
      System.setProperty("spice.cutoff", "2026-01-01T00:00:00Z");
      assertFalse(PassClaims.of(null).additionalClaims().containsKey("x-cutoff"));
      assertFalse(
          PassClaims.of(pass("{\"x-uuid-project\":\"p\"}"))
              .additionalClaims()
              .containsKey("x-cutoff"));
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
