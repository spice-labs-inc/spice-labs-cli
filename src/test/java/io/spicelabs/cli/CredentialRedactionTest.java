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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nothing carrying the Spice Pass may print it.
 *
 * <p>These types are safe today because none of them declares a {@code toString} — they inherit
 * {@code Object}'s, which prints only a class name and a hash. That safety is accidental, and one
 * {@code record} or {@code @Data} away from ending. These assertions make the redaction a stated
 * property, so removing it fails here rather than in somebody's log.
 */
class CredentialRedactionTest {

  /** Shaped like a real pass, so a substring match means something. */
  private static final String PASS = "eyJhbGciOiJSUzI1NiJ9."
      + Base64.getUrlEncoder().withoutPadding().encodeToString(
          "{\"x-uuid-project\":\"p\",\"x-upload-server\":\"https://upload.example\"}".getBytes())
      + ".c2lnbmF0dXJl";

  @Test
  @DisplayName("the context does not print the pass it holds")
  void contextRedactsPass() {
    DefaultSpiceContext context = new DefaultSpiceContext("1.2.3", PASS);

    String printed = context.toString();
    assertFalse(printed.contains(PASS), "context toString leaked the pass: " + printed);
    assertTrue(printed.contains("redacted"), printed);
    // Still reachable by name: this is about what gets printed, not what gets used.
    assertTrue(context.spicePass().isPresent());
  }

  @Test
  @DisplayName("claims do not print the project and endpoint they carry")
  void claimsRedactContents() {
    String printed = PassClaims.of(PASS).toString();

    assertFalse(printed.contains("upload.example"), "claims toString leaked a claim: " + printed);
    assertFalse(printed.contains(PASS), printed);
    assertTrue(printed.contains("redacted"), printed);
  }
}
