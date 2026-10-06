// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
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
  void anUnknownUrlPrefixNamesTheSupportedOnes() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput("s3://bucket/app").path());
    assertTrue(ex.getMessage().contains("The prefix s3:// is not supported"), ex.getMessage());
    assertTrue(ex.getMessage().contains("use docker://<image> or oci://<image>"), ex.getMessage());
  }

  @Test
  void ociPrefixNamesTheSameRegistryPull() {
    SurveyInput input = new SurveyInput("oci://ghcr.io/acme/app@sha256:abc123");
    assertTrue(input.isImage());
    assertEquals("ghcr.io/acme/app@sha256:abc123", input.image());
  }

  @Test
  void ociPrefixWithNoImageIsRefused() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput("oci://").image());
    assertEquals("oci:// needs an image name, for example oci://nginx:1.27", ex.getMessage());
  }

  /** oci: and oci-layout: name a saved image folder elsewhere; here that is just a path. */
  @Test
  void ociLayoutPrefixesPointAtThePlainPath() {
    for (String value : new String[] {"oci:./saved-image", "oci-layout:./saved-image:latest"}) {
      var ex = assertThrows(IllegalArgumentException.class, () -> new SurveyInput(value).path());
      assertTrue(ex.getMessage().contains("is not supported"), ex.getMessage());
      assertTrue(ex.getMessage().contains("use docker://<image> or oci://<image>"), ex.getMessage());
      assertTrue(ex.getMessage().endsWith("To survey a saved image folder (an OCI layout), give the folder's path with no prefix"),
          ex.getMessage());
    }
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

  @Test
  void theDefaultSubjectIsTheRepositoryNameWithoutTagOrDigest() {
    assertEquals("ghcr.io/acme/web", SurveyInput.defaultSubject("ghcr.io/acme/web:2.4.1"));
    assertEquals("ghcr.io/acme/web", SurveyInput.defaultSubject("ghcr.io/acme/web@sha256:abc123"));
    assertEquals("nginx", SurveyInput.defaultSubject("nginx:1.27"));
    assertEquals("nginx", SurveyInput.defaultSubject("nginx"));
    assertEquals("nginx", SurveyInput.defaultSubject("docker.io/library/nginx:1.27"));
    assertEquals("bitnami/redis", SurveyInput.defaultSubject("bitnami/redis:7.2"));
    assertEquals("bitnami/redis", SurveyInput.defaultSubject("docker.io/bitnami/redis:7.2"));
    assertEquals("localhost:5000/team/app", SurveyInput.defaultSubject("localhost:5000/team/app:1.0"));
    assertEquals("localhost:5000/app", SurveyInput.defaultSubject("localhost:5000/app"));
  }

  /** A lone docker:// or oci:// image is the input, and the subject comes from it. */
  @Test
  void anImageAloneNeedsNoSubject() throws Exception {
    for (String image : new String[] {"docker://ghcr.io/acme/web:2.4.1", "oci://ghcr.io/acme/web:2.4.1"}) {
      SurveyImageCommand[] used = new SurveyImageCommand[1];
      SurveyInventoryCommand cmd = imageCapturing(used);
      int rc = new CommandLine(cmd).execute(image, "--no-upload", "--output", dir.toString());
      assertEquals(0, rc, image);
      assertEquals("ghcr.io/acme/web:2.4.1", used[0].image);
      assertEquals("ghcr.io/acme/web", used[0].effectiveSubject("ghcr.io/acme/web:2.4.1"));
    }
  }

  @Test
  void anExplicitSubjectBeforeAnImageStillWins() throws Exception {
    SurveyImageCommand[] used = new SurveyImageCommand[1];
    int rc = new CommandLine(imageCapturing(used))
        .execute("web-store", "docker://ghcr.io/acme/web:2.4.1", "--no-upload", "--output", dir.toString());
    assertEquals(0, rc);
    assertEquals("web-store", used[0].effectiveSubject("ghcr.io/acme/web:2.4.1"));
  }

  @Test
  void aPathAloneStillNeedsASubject() throws Exception {
    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new SurveyInventoryCommand());
    cmd.setErr(new PrintWriter(err, true));
    assertNotEquals(0, cmd.execute(dir.toString()));
    assertTrue(err.toString().contains("Missing required parameter: '<input>'"), err.toString());
  }

  @Test
  void nothingAtAllNamesBothParameters() throws Exception {
    StringWriter err = new StringWriter();
    CommandLine cmd = new CommandLine(new SurveyInventoryCommand());
    cmd.setErr(new PrintWriter(err, true));
    assertNotEquals(0, cmd.execute());
    assertTrue(err.toString().contains("Missing required parameters: '<subject>', '<input>'"), err.toString());
  }

  @Test
  void theUsageShowsBothForms() {
    String usage = new CommandLine(new SurveyInventoryCommand()).getUsageMessage();
    assertTrue(usage.contains("spice survey inventory [OPTIONS] <subject> <input>"), usage);
    assertTrue(usage.contains("spice survey inventory [OPTIONS] [<subject>] (docker|oci)://<image>"), usage);
  }

  private static SurveyInventoryCommand imageCapturing(SurveyImageCommand[] used) {
    return new SurveyInventoryCommand() {
      @Override
      SurveyImageCommand newImageCommand() {
        used[0] = new SurveyImageCommand() {
          @Override
          void pull(String ref, Path layoutDir) throws Exception {
            Files.createDirectories(layoutDir.resolve("layout.oci"));
          }

          @Override
          int survey(String ref, Path layoutDir) {
            return 0;
          }
        };
        return used[0];
      }
    };
  }
}
