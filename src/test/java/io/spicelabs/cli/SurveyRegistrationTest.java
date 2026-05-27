// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class SurveyRegistrationTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void register_postsToSurveys_andReturnsServerTimestamp() throws Exception {
    UUID parentId = UUID.randomUUID();
    UUID analyzeSubJobId = UUID.randomUUID();
    server.enqueue(new MockResponse()
        .setResponseCode(201)
        .setBody("{\"parent_id\":\"" + parentId
            + "\",\"submission_timestamp\":\"2026-05-20T12:00:00Z\""
            + ",\"analyze_sub_job_id\":\"" + analyzeSubJobId + "\"}"));

    // The pass's upload URL points at the mock server; ginger-j derives /surveys from it.
    String uploadServer = server.url("/api/v1/org/o/project/p/bundle/upload").toString();
    String pass = passWithUploadServer(uploadServer);

    SurveyRegistration.Context ctx =
        SurveyRegistration.register(pass, "INVENTORY_SURVEY", "my-subject", null);

    assertEquals(parentId, ctx.parentId());
    assertEquals(analyzeSubJobId, ctx.analyzeSubJobId(),
        "analyzeSubJobId must round-trip from initSurvey for ANALYZE progress publishes");
    assertEquals("2026-05-20T12:00:00Z", ctx.submissionTimestamp().toString(),
        "the bundle date must be the server value, verbatim");
    assertNotNull(ctx.idempotencyKey());
    assertTrue(ctx.userAgent().startsWith("spice-labs-cli/"));

    RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("POST", req.getMethod());
    assertTrue(req.getPath().endsWith("/api/v1/org/o/project/p/surveys"),
        "should POST to the /surveys sibling of the upload URL, got: " + req.getPath());
    assertNotNull(req.getHeader("Idempotency-Key"));
    assertTrue(req.getHeader("User-Agent").startsWith("spice-labs-cli/"));
    String body = req.getBody().readUtf8();
    assertTrue(body.contains("\"jobType\":\"INVENTORY_SURVEY\""), body);
    assertTrue(body.contains("\"tag\":\"my-subject\""), body);
  }

  @Test
  void register_missingUploadServerClaim_throws() {
    String pass = b64("{\"alg\":\"none\"}") + "." + b64("{\"x-uuid-project\":\"p\"}") + ".sig";
    assertThrows(IllegalArgumentException.class,
        () -> SurveyRegistration.register(pass, "INVENTORY_SURVEY", "subj", null));
  }

  /** A minimal (unsigned) spice pass carrying just the x-upload-server claim. */
  private static String passWithUploadServer(String uploadServer) {
    return b64("{\"alg\":\"none\",\"typ\":\"JWT\"}")
        + "." + b64("{\"x-upload-server\":\"" + uploadServer + "\",\"x-uuid-project\":\"p\"}")
        + ".sig";
  }

  private static String b64(String json) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
