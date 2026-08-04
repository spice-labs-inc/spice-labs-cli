// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolatileSubjectDetectorTest {

  @ParameterizedTest
  @CsvSource({
      "my-app-2026-08-03,   a date,                  my-app",
      "myapp_20260803,      a date,                  myapp",
      "my-app-12345,        a build or run number,   my-app",
      "build.1234567890,    a build or run number,   build",
      "my-app-1a2b3c4,      a commit hash,           my-app",
      "svc-4e9f1c2ab7d8e6f0a1b2c3d4e5f60718293a4b5c, a commit hash, svc",
      "my-app-v1.2.3,       a version number,        my-app",
      "my-app-1.2,          a version number,        my-app",
      "api:v10.0.1,         a version number,        api",
  })
  void volatileTailsAreDetected(String subject, String kind, String stem) {
    Optional<VolatileSubjectDetector.Volatility> v = VolatileSubjectDetector.check(subject);
    assertTrue(v.isPresent(), subject + " should look volatile");
    assertEquals(kind, v.get().kind());
    assertEquals(stem, v.get().stableStem());
  }

  @ParameterizedTest
  @CsvSource({
      "d7f81a94-9a2b-4c6d-8e1f-0a2b3c4d5e6f, ''",
      "my-app-d7f81a94-9a2b-4c6d-8e1f-0a2b3c4d5e6f, my-app",
  })
  void uuidTailsAreDetected(String subject, String stem) {
    Optional<VolatileSubjectDetector.Volatility> v = VolatileSubjectDetector.check(subject);
    assertTrue(v.isPresent(), subject + " should look volatile");
    assertEquals("a UUID", v.get().kind());
    assertEquals(stem == null ? "" : stem, v.get().stableStem());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "payments-api",
      "mobile-android",
      "my-app",
      "my-app2",
      "api-v2",
      "release-x",
      "my-app-build-4711",  // four digits: below the five-digit volatile threshold
      "spice-labs-cli",
      "app-beta",
  })
  void stableNamesAreNotFlagged(String subject) {
    assertTrue(VolatileSubjectDetector.check(subject).isEmpty(),
        subject + " should not look volatile");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  "})
  void blankNamesAreNotFlagged(String subject) {
    assertTrue(VolatileSubjectDetector.check(subject).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"20260803"})
  void entirelyVolatileNameHasEmptyStem(String subject) {
    Optional<VolatileSubjectDetector.Volatility> v = VolatileSubjectDetector.check(subject);
    assertTrue(v.isPresent());
    assertEquals("", v.get().stableStem());
  }
}
