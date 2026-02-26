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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

public class SpicePassDecoder {

  private static final Logger log = LoggerFactory.getLogger(SpicePassDecoder.class);
  private final DecodedJWT jwt;

  private static final DateTimeFormatter HUMAN_DATE =
      DateTimeFormatter.ofPattern("EEE MMM dd yyyy, hh:mm:ss a z").withZone(ZoneOffset.UTC);

  private static final Map<String, String> CLAIM_NAMES = new LinkedHashMap<>();
  static {
    CLAIM_NAMES.put("x-type", "Token Type");
    CLAIM_NAMES.put("jti", "JWT ID");
    CLAIM_NAMES.put("iat", "Issued At");
    CLAIM_NAMES.put("exp", "Expires At");
    CLAIM_NAMES.put("nbf", "Not Before");
    CLAIM_NAMES.put("iss", "Issuer");
    CLAIM_NAMES.put("sub", "Subject");
    CLAIM_NAMES.put("aud", "Audience");
    CLAIM_NAMES.put("x-uuid-org", "Organization ID");
    CLAIM_NAMES.put("x-uuid-type", "Organization ID");
    CLAIM_NAMES.put("x-uuid-user", "User ID");
    CLAIM_NAMES.put("x-uuid-project", "Project ID");
    CLAIM_NAMES.put("demo", "Demo Project");
    CLAIM_NAMES.put("x-upload-server", "Upload Server");
    CLAIM_NAMES.put("x-public-key", "Public Key");
    CLAIM_NAMES.put("x-challenge", "Challenge");
  }

  public SpicePassDecoder(String spicePass) {
    if (spicePass == null || spicePass.isBlank()) {
      throw new IllegalArgumentException("SPICE_PASS cannot be null or blank");
    }
    this.jwt = JWT.decode(spicePass);
  }

  public String getProjectId() {
    Claim projectIdClaim = jwt.getClaim("x-uuid-project");
    if (projectIdClaim.isNull()) {
      projectIdClaim = jwt.getClaim("project_id");
    }
    if (projectIdClaim.isNull()) {
      projectIdClaim = jwt.getClaim("projectId");
    }
    return projectIdClaim.isNull() ? null : projectIdClaim.asString();
  }

  public Instant getExpiresAt() {
    return jwt.getExpiresAt() != null ? jwt.getExpiresAt().toInstant() : null;
  }

  public String getStatus() {
    if (jwt.getExpiresAt() == null) {
      return "No expiration";
    }
    Instant now = Instant.now();
    Instant exp = jwt.getExpiresAt().toInstant();
    return exp.isBefore(now) ? "EXPIRED" : "Valid";
  }

  public void printFullInfo() {
    log.info("JWT Header:");
    log.info("  Algorithm: {}", jwt.getAlgorithm());
    log.info("  Type: {}", jwt.getType());
    log.info("");

    Map<String, Claim> allClaims = new LinkedHashMap<>(jwt.getClaims());

    int nameWidth = 18;
    int claimWidth = 18;

    log.info("Claims:");
    log.info("  {}{}{}", pad("Name", nameWidth), pad("Claim", claimWidth), "Value");
    log.info("  {}{}{}",
        repeat('\u2500', nameWidth - 2) + "  ",
        repeat('\u2500', claimWidth - 2) + "  ",
        repeat('\u2500', 50));

    for (Map.Entry<String, String> known : CLAIM_NAMES.entrySet()) {
      String key = known.getKey();
      Claim claim = allClaims.remove(key);
      if (claim == null || claim.isNull()) continue;

      String friendlyName = known.getValue();
      String value = formatClaimValue(key, claim);
      log.info("  {}{}{}", pad(friendlyName, nameWidth), pad(key, claimWidth), value);
    }

    for (Map.Entry<String, Claim> entry : allClaims.entrySet()) {
      String key = entry.getKey();
      Claim claim = entry.getValue();
      if (claim.isNull()) continue;

      String value = formatClaimValue(key, claim);
      log.info("  {}{}{}", pad(key, nameWidth), pad(key, claimWidth), value);
    }

    log.info("");
    log.info("  Status: {}", formatStatus());
  }

  private String formatClaimValue(String key, Claim claim) {
    if (key.equals("iat") || key.equals("exp") || key.equals("nbf")) {
      try {
        long epoch = claim.asLong();
        Instant instant = Instant.ofEpochSecond(epoch);
        return epoch + "  (" + HUMAN_DATE.format(instant) + ")";
      } catch (Exception e) {
        // fall through
      }
    }

    if (key.equals("x-public-key")) {
      String val = claim.asString();
      if (val != null && val.length() > 40) {
        return val.substring(0, 40) + "... (truncated)";
      }
    }

    if (claim.asString() != null) {
      return claim.asString();
    }
    if (claim.asLong() != null) {
      return claim.asLong().toString();
    }
    return claim.toString();
  }

  private String formatStatus() {
    if (jwt.getExpiresAt() == null) {
      return "No expiration";
    }
    Instant now = Instant.now();
    Instant exp = jwt.getExpiresAt().toInstant();
    Duration diff = Duration.between(now, exp).abs();
    String relative = formatDuration(diff);

    if (exp.isBefore(now)) {
      return "EXPIRED (" + relative + " ago)";
    } else {
      return "Valid (expires in " + relative + ")";
    }
  }

  private static String formatDuration(Duration d) {
    return DurationFormatUtils.formatDurationWords(d.toMillis(), true, true);
  }

  private static String pad(String s, int width) {
    if (s.length() >= width) return s + "  ";
    return s + " ".repeat(width - s.length());
  }

  private static String repeat(char c, int count) {
    return String.valueOf(c).repeat(count);
  }
}
