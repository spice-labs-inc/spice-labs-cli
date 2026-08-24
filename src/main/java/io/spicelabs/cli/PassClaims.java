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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

import io.spicelabs.cli.spi.SpicePassClaims;

/**
 * The claims of a decoded Spice Pass.
 *
 * <p>This is the CLI's implementation of {@link SpicePassClaims} and the <em>only</em> place a
 * pass is decoded for the SPI. Built-in commands and plugins both read from a value of this
 * type, so they cannot disagree about what the pass says.
 *
 * <p>The registered JWT claims (RFC 7519) come out through typed accessors; everything else —
 * the Spice-specific {@code x-*} claims and anything the platform mints later — lands in
 * {@link #additionalClaims()} verbatim, as JSON-typed {@code java.*} values with integral
 * numbers normalised to {@link Long}.
 *
 * <p>A missing, blank or undecodable pass yields {@link SpicePassClaims#EMPTY} rather than an
 * exception: the claims are a convenience here, and no command should fail to start because
 * of them. Commands that genuinely require the pass report that themselves.
 *
 * <p>These values were previously republished as system properties (notably
 * {@code -Dspice.cutoff}) so that in-process plugins could reach them. That channel is gone:
 * claims now travel through {@link io.spicelabs.cli.spi.SpiceContext#passClaims()}, which
 * makes them discoverable and impossible to forge from the command line.
 */
final class PassClaims implements SpicePassClaims {

  private static final Logger log = LoggerFactory.getLogger(PassClaims.class);

  /** The claims RFC 7519 registers, exposed through the typed accessors and therefore
    * excluded from {@link #additionalClaims()}. */
  private static final Set<String> REGISTERED =
      Set.of("iss", "sub", "aud", "exp", "nbf", "iat", "jti");

  private final DecodedJWT jwt;
  private final Map<String, Object> additional;

  private PassClaims(DecodedJWT jwt) {
    this.jwt = jwt;
    Map<String, Object> extras = new LinkedHashMap<>();
    for (Map.Entry<String, Claim> entry : jwt.getClaims().entrySet()) {
      if (REGISTERED.contains(entry.getKey()) || entry.getValue().isNull()) {
        continue;
      }
      extras.put(entry.getKey(), plain(entry.getValue().as(Object.class)));
    }
    this.additional = Map.copyOf(extras);
  }

  /**
   * The claims carried by the given pass, or {@link SpicePassClaims#EMPTY} if it is absent
   * or undecodable.
   *
   * <p>Commands that accept a {@code --spice-pass} override must call this with the pass they
   * actually resolved, so the claims match the credential in use.
   */
  static SpicePassClaims of(String spicePass) {
    if (spicePass == null || spicePass.isBlank()) {
      return SpicePassClaims.EMPTY;
    }
    try {
      return new PassClaims(JWT.decode(spicePass));
    } catch (RuntimeException e) {
      log.debug("Could not read claims from the Spice Pass: {}", e.getMessage());
      return SpicePassClaims.EMPTY;
    }
  }

  /**
   * Normalise a Jackson-decoded JSON value to the SPI's promised types: integral numbers are
   * always {@link Long} (Jackson hands back {@link Integer} for small ones), fractional ones
   * {@link Double}, and lists and maps are converted recursively.
   */
  private static Object plain(Object value) {
    if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
      return ((Number) value).longValue();
    }
    if (value instanceof Float) {
      return ((Number) value).doubleValue();
    }
    if (value instanceof List<?> list) {
      List<Object> converted = new ArrayList<>(list.size());
      for (Object element : list) {
        converted.add(plain(element));
      }
      return List.copyOf(converted);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        converted.put(String.valueOf(entry.getKey()), plain(entry.getValue()));
      }
      return Map.copyOf(converted);
    }
    return value;
  }

  @Override
  public Optional<String> issuer() {
    return Optional.ofNullable(jwt.getIssuer());
  }

  @Override
  public Optional<String> subject() {
    return Optional.ofNullable(jwt.getSubject());
  }

  @Override
  public List<String> audience() {
    List<String> audience = jwt.getAudience();
    return audience == null ? List.of() : List.copyOf(audience);
  }

  @Override
  public Optional<Instant> expiresAt() {
    return Optional.ofNullable(jwt.getExpiresAtAsInstant());
  }

  @Override
  public Optional<Instant> notBefore() {
    return Optional.ofNullable(jwt.getNotBeforeAsInstant());
  }

  @Override
  public Optional<Instant> issuedAt() {
    return Optional.ofNullable(jwt.getIssuedAtAsInstant());
  }

  @Override
  public Optional<String> jwtId() {
    return Optional.ofNullable(jwt.getId());
  }

  @Override
  public Map<String, Object> additionalClaims() {
    return additional;
  }

  /**
   * Deliberately says nothing about the claims.
   *
   * <p>The decoded claims are not themselves the credential, but they name the project and
   * organisation a run belongs to and the endpoint it uploads to. A caller that wants one of those
   * can ask for it; none of them needs to arrive by way of a log line about something else.
   */
  @Override
  public String toString() {
    return "PassClaims[" + additional.size() + " claims, <redacted>]";
  }

}
