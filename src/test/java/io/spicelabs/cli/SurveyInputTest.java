// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

/** {@code spice survey inventory}'s input: a file or folder, or a {@code docker://} image. */
class SurveyInputTest {

  @TempDir
  Path dir;

  @Test
  void anExistingFolderIsAPath() throws Exception {
    SurveyInput input = new SurveyInput(dir.toString());
    assertFalse(input.isImage());
    assertEquals(dir, input.path());
  }

  @Test
  void anExistingFileIsAPath() throws Exception {
    Path jar = Files.writeString(dir.resolve("app.jar"), "x");
    assertEquals(jar, new SurveyInput(jar.toString()).path());
  }

  @Test
  void anExistingFolderWithAColonInItsNameIsStillAPath() throws Exception {
    Path odd = Files.createDirectories(dir.resolve("nginx:1.27"));
    assertEquals(odd, new SurveyInput(odd.toString()).path());
  }

  @Test
  void dockerPrefixNamesAnImageWithATag() {
    SurveyInput input = new SurveyInput("docker://nginx:1.27");
    assertTrue(input.isImage());
    assertEquals("nginx:1.27", input.image());
  }

  @Test
  void dockerPrefixNamesAnImageWithARegistryAndDigest() {
    SurveyInput input = new SurveyInput("docker://ghcr.io/spice-labs-inc/grinder@sha256:abc123");
    assertTrue(input.isImage());
    assertEquals("ghcr.io/spice-labs-inc/grinder@sha256:abc123", input.image());
  }

  @Test
  void dockerPrefixWithNoImageIsRefused() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput("docker://").image());
    assertEquals("docker:// needs an image name, for example docker://nginx:1.27", ex.getMessage());
  }

  @Test
  void anImageLookingValueGetsTheDockerHint() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput("nginx:1.27").path());
    assertEquals("No such file or folder: nginx:1.27. To survey a container image, use docker://nginx:1.27",
        ex.getMessage());
  }

  @Test
  void aDigestGetsTheDockerHint() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput("ubuntu@sha256:abc").path());
    assertTrue(ex.getMessage().endsWith("use docker://ubuntu@sha256:abc"), ex.getMessage());
  }

  @Test
  void aRegistryHostGetsTheDockerHint() {
    var ex = assertThrows(IllegalArgumentException.class,
        () -> new SurveyInput("ghcr.io/spice-labs-inc/grinder").path());
    assertTrue(ex.getMessage().endsWith("use docker://ghcr.io/spice-labs-inc/grinder"), ex.getMessage());
  }

  @Test
  void dockerWithOneColonIsCorrected() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput("docker:nginx").path());
    assertEquals("No such file or folder: docker:nginx. To survey a container image, use docker://nginx",
        ex.getMessage());
  }

  @Test
  void anUnknownUrlPrefixNamesTheSupportedOne() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput("oci://nginx").path());
    assertTrue(ex.getMessage().contains("The prefix oci:// is not supported"), ex.getMessage());
    assertTrue(ex.getMessage().contains("docker://<image>"), ex.getMessage());
  }

  @Test
  void anotherContainersTransportIsRefusedByName() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput("oci-archive:./img.tar").path());
    assertTrue(ex.getMessage().contains("The prefix oci-archive: is not supported"), ex.getMessage());
  }

  @Test
  void aMissingPathKeepsTheMessageItAlwaysHad() {
    String missing = dir.resolve("does-not-exist").toString();
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput(missing).path());
    assertEquals("Input path does not exist: " + missing, ex.getMessage());
  }

  @Test
  void aBareWordIsAMissingPathNotAnImage() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput("build").path());
    assertEquals("Input path does not exist: build", ex.getMessage());
  }

  @Test
  void pathsAreNeverReadAsImages() {
    for (String value : new String[] {"./a:b", "../a:b", "/opt/a:b", "~/a:b", "C:\\work\\app", "c:/work",
        "build/libs", "my dir/a:b", ""}) {
      assertFalse(SurveyInput.looksLikeImage(value), value);
    }
    for (String value : new String[] {"nginx:1.27", "nginx@sha256:abc", "ghcr.io/a/b", "localhost/a",
        "localhost:5000/a"}) {
      assertTrue(SurveyInput.looksLikeImage(value), value);
    }
  }

  /** picocli hands the value over as typed: a Path would collapse docker:// to docker:/. */
  @Test
  void theCommandLineKeepsTheValueAsTyped() {
    SurveyInventoryCommand cmd = new SurveyInventoryCommand();
    new CommandLine(cmd).parseArgs("my-app", "docker://ghcr.io/a/b:1.0");
    assertEquals("docker://ghcr.io/a/b:1.0", cmd.source.raw());
    assertTrue(cmd.source.isImage());
  }
}
