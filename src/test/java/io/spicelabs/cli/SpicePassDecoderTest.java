// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class SpicePassDecoderTest {

  @Test
  void getUploadServer_returnsClaim() {
    String pass = b64("{\"alg\":\"none\"}")
        + "." + b64("{\"x-upload-server\":\"https://host/api/v1/org/o/project/p/bundle/upload\"}")
        + ".sig";
    assertEquals("https://host/api/v1/org/o/project/p/bundle/upload",
        new SpicePassDecoder(pass).getUploadServer());
  }

  @Test
  void getUploadServer_nullWhenClaimAbsent() {
    String pass = b64("{\"alg\":\"none\"}") + "." + b64("{\"x-uuid-project\":\"p\"}") + ".sig";
    assertNull(new SpicePassDecoder(pass).getUploadServer());
  }

  private static String b64(String json) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
