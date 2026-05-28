// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.spicelabs.ginger.DirectUploadService;

/**
 * Registers a survey with the server before any local work and returns the server-minted
 * submission timestamp. That timestamp is the authoritative bundle date everywhere
 * downstream — an uploaded bundle never carries the local clock.
 */
final class SurveyRegistration {

  /**
   * Server-supplied survey identity and timestamp, threaded through survey + upload.
   * {@code analyzeSubJobId} is the sub-job daikon mints at {@code initSurvey} for the
   * local analyze pass — progress publishes during goat-rodeo / JFR parsing target it.
   * It may be {@code null} when running against an older daikon that doesn't mint it.
   */
  record Context(UUID parentId, Instant submissionTimestamp, UUID idempotencyKey, String userAgent,
                 UUID analyzeSubJobId) {}

  private SurveyRegistration() {}

  static Context register(String spicePass, String jobType, String subject, Map<String, Object> jsonTags)
      throws Exception {
    String uploadServer = new SpicePassDecoder(spicePass).getUploadServer();
    if (uploadServer == null || uploadServer.isBlank()) {
      throw new IllegalArgumentException("SPICE_PASS is missing the x-upload-server claim");
    }

    UUID idempotencyKey = UUID.randomUUID();
    String userAgent = "spice-labs-cli/" + SpiceLabsCLI.VersionProvider.getVersionString();

    DirectUploadService.InitSurveyRequest request =
        new DirectUploadService.InitSurveyRequest(jobType, subject, jsonTags);

    DirectUploadService.InitSurveyResponse response =
        new DirectUploadService().initSurvey(uploadServer, spicePass, request, idempotencyKey, userAgent);

    Instant submissionTimestamp = response.submissionTimestamp();
    if (submissionTimestamp == null) {
      throw new IllegalStateException("initSurvey response missing submission_timestamp");
    }
    return new Context(response.parentId(), submissionTimestamp, idempotencyKey, userAgent,
        response.analyzeSubJobId());
  }
}
