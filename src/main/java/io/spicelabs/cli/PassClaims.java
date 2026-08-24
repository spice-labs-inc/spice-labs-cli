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
 * <p>The pass is parsed here, in the CLI, rather than in ginger-j, which parsed it first and
 * still parses the claims it needs to upload ({@code x-public-key}, {@code x-upload-server},
 * {@code x-uuid-project}, {@code x-challenge}, {@code exp}). Those it reads to do its own job.
 * Claim semantics for everyone else belong here: the SPI is a CLI contract that ginger-j knows
 * nothing about, plugins mount into {@code spice} rather than into the uploader, and the other
 * consumers — GoatRodeo's cutoff, {@code pass decode}, the Allspice plugin — do not go through
 * ginger-j at all. Owning it in the uploader would point the dependency the wrong way.
 *
 * <p>The two overlap today on the claims both happen to read, which is worth converging, but
 * they cannot contradict each other about scope: nothing outside ginger-j consults ginger-j's
 * copy.
 *
 * <p>Claims reach in-process plugins through
 * {@link io.spicelabs.cli.spi.SpiceContext#passClaims()} and through nothing else. They are
 * deliberately not republished as system properties, which is otherwise the obvious way to hand
 * a value to code that {@code ServiceLoader} has loaded into this same JVM: a property can be
 * set with {@code -D} on the command line, so a claim like {@code x-cutoff} would become
 * something the caller can widen rather than something the pass fixes.
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
   * The artifact cutoff these claims carry: artifacts published after this instant are out of
   * scope for a survey.
   *
   * <p>This takes claims rather than a pass deliberately. A caller that already holds decoded
   * claims — which, thanks to {@link DefaultSpiceContext}, every caller does — can ask for the
   * cutoff without decoding the pass a second time to get it.
   *
   * <p><strong>This is the reference reading of {@code x-cutoff}, and it is deliberately not
   * the only one.</strong> Plugins reach claims through the SPI, which hands over
   * {@code additionalClaims()} and no interpretation, so a plugin that wants the cutoff — the
   * Allspice registry is the first — reads the claim itself. Promoting this to a default method
   * on {@code SpicePassClaims} would make it shared, but costs a release across plugin-api,
   * spice-bom and every plugin; two small implementations are the cheaper trade for now. Any
   * other implementation must agree on all three of these, because each way of getting them
   * wrong yields a survey that silently covers almost nothing while exiting successfully:
   *
   * <ul>
   *   <li><strong>Epoch seconds, not milliseconds.</strong> Read as millis, a 2026 cutoff lands
   *       in January 1970 and drops the entire estate.
   *   <li><strong>Absent means no cutoff</strong>, never {@link Instant#EPOCH}. Defaulting to
   *       the epoch turns "this pass does not narrow scope" into "exclude everything".
   *   <li><strong>A non-numeric value is ignored</strong>, with a warning, and again means no
   *       cutoff. A malformed claim must not be read as a bound of zero.
   * </ul>
   */
  static Optional<Instant> cutoff(SpicePassClaims claims) {
    Object value = claims.additionalClaims().get("x-cutoff");
    if (value == null) {
      return Optional.empty();
    }
    if (value instanceof Long seconds) {
      return Optional.of(Instant.ofEpochSecond(seconds));
    }
    log.warn("Ignoring x-cutoff claim: expected epoch seconds, got {}", value);
    return Optional.empty();
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
