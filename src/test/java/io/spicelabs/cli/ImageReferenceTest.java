// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ImageReferenceTest {

  @Test
  void bareNameBecomesDockerLibraryLatest() {
    assertEquals("docker.io/library/nginx:latest", ImageReference.normalize("nginx"));
  }

  @Test
  void bareNameWithTag() {
    assertEquals("docker.io/library/nginx:1.25", ImageReference.normalize("nginx:1.25"));
  }

  @Test
  void userScopedNameMapsToDockerIo() {
    assertEquals("docker.io/spicelabs/grinder:latest", ImageReference.normalize("spicelabs/grinder"));
  }

  @Test
  void hostIsPreserved() {
    assertEquals("ghcr.io/spice-labs-inc/grinder:0.1.0",
        ImageReference.normalize("ghcr.io/spice-labs-inc/grinder:0.1.0"));
  }

  @Test
  void hostNameWithoutTagGetsLatest() {
    assertEquals("docker.io/library/ubuntu:latest", ImageReference.normalize("ubuntu"));
  }

  @Test
  void explicitDockerLoopbackHostIsPreserved() {
    assertEquals("localhost:5000/myapp:latest", ImageReference.normalize("localhost:5000/myapp"));
  }

  @Test
  void digestIsPreservedVerbatim() {
    assertEquals("docker.io/library/ubuntu@sha256:abc123",
        ImageReference.normalize("ubuntu@sha256:abc123"));
  }

  @Test
  void digestWithHostPreserved() {
    assertEquals("ghcr.io/org/img@sha256:xyz",
        ImageReference.normalize("ghcr.io/org/img@sha256:xyz"));
  }

  @Test
  void explicitDockerIoIsNotReExpanded() {
    assertEquals("docker.io/library/alpine:3.19",
        ImageReference.normalize("docker.io/library/alpine:3.19"));
  }

  @Test
  void portOnlyInFirstComponentIsNotATag() {
    // localhost:5000 is a registry port; the tag parser must not grab it.
    assertEquals("localhost:5000/app:v2", ImageReference.normalize("localhost:5000/app:v2"));
  }

  @Test
  void blankReferenceRejected() {
    assertThrows(IllegalArgumentException.class, () -> ImageReference.normalize("  "));
    assertThrows(IllegalArgumentException.class, () -> ImageReference.normalize(null));
  }

  @Test
  void blankDigestRejected() {
    assertThrows(IllegalArgumentException.class, () -> ImageReference.normalize("ubuntu@"));
  }

  @Test
  void whitespaceIsTrimmed() {
    assertEquals("docker.io/library/nginx:latest", ImageReference.normalize("  nginx  "));
  }
}
