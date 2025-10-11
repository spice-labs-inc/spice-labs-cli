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

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;

public class SpicePassDecoder {

  private static final Logger log = LoggerFactory.getLogger(SpicePassDecoder.class);
  private final DecodedJWT jwt;

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

    log.info("JWT Claims:");
    log.info("  Issuer: {}", jwt.getIssuer());
    log.info("  Subject: {}", jwt.getSubject());
    log.info("  Audience: {}", jwt.getAudience());

    String projectId = getProjectId();
    if (projectId != null) {
      log.info("  Project ID: {}", projectId);
    }

    if (jwt.getIssuedAt() != null) {
      log.info("  Issued At: {} ({})", jwt.getIssuedAt(), jwt.getIssuedAt().toInstant());
    }

    if (jwt.getExpiresAt() != null) {
      log.info("  Expires At: {} ({})", jwt.getExpiresAt(), jwt.getExpiresAt().toInstant());
      Instant now = Instant.now();
      Instant exp = jwt.getExpiresAt().toInstant();
      if (exp.isBefore(now)) {
        log.info("  Status: EXPIRED");
      } else {
        log.info("  Status: Valid");
      }
    }

    if (jwt.getNotBefore() != null) {
      log.info("  Not Before: {} ({})", jwt.getNotBefore(), jwt.getNotBefore().toInstant());
    }

    log.info("All Claims:");
    for (Map.Entry<String, Claim> entry : jwt.getClaims().entrySet()) {
      Claim claim = entry.getValue();
      String value;
      if (claim.isNull()) {
        value = "null";
      } else if (claim.asString() != null) {
        value = claim.asString();
      } else {
        value = claim.toString();
      }
      log.info("  {}: {}", entry.getKey(), value);
    }
  }
}
