// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the delegation boundary: {@code survey image} must pull an OCI layout and then hand
 * that layout to the shared inventory survey. The {@code pull} seam is overridden so the
 * test exercises layout creation + the inventory handoff without needing a daemon or network.
 */
class SurveyImageCommandTest {

  @TempDir
  Path tempDir;

  /**
   * {@code pull} and {@code survey} are write seams. {@code run()} creates the layout dir,
   * calls {@code pull} into it, and passes the resulting {@code layout.oci} to {@code survey}.
   */
  @Test
  void runCreatesLayoutAndTriggersPullAndSurvey() throws Exception {
    Path marker = tempDir.resolve("marker");
    Path[] pullLayout = new Path[1];
    Path[] surveyLayout = new Path[1];
    SurveyImageCommand cmd = new SurveyImageCommand() {
      @Override
      void pull(String ref, Path layoutDir) throws Exception {
        Files.writeString(marker, ref);
        pullLayout[0] = layoutDir;
        // Simulate what oras leaves behind: an OCI layout dir under the base.
        Files.createDirectories(layoutDir.resolve("layout.oci"));
      }

      @Override
      int survey(String ref, Path layoutDir) {
        surveyLayout[0] = layoutDir;
        return 0;
      }
    };
    cmd.subject = "my-app";
    cmd.image = "nginx";

    int rc = cmd.run();
    assertEquals(0, rc);
    assertEquals("docker.io/library/nginx:latest", Files.readString(marker),
        "pull must be called with the normalized ref");
    assertNotNull(surveyLayout[0], "survey must receive the pulled layout dir");
    assertEquals(pullLayout[0], surveyLayout[0],
        "survey must receive the same layout dir pull populated");
  }

  /** Without {@code --subject}, the survey is tagged with the normalized image ref. */
  @Test
  void subjectDefaultsToImageRef() {
    SurveyImageCommand cmd = new SurveyImageCommand();
    cmd.image = "nginx";
    assertEquals("docker.io/library/nginx:latest",
        cmd.effectiveSubject("docker.io/library/nginx:latest"));
  }

  /** {@code --subject} overrides the ref-derived tag. */
  @Test
  void subjectOptionOverridesImageRef() {
    SurveyImageCommand cmd = new SurveyImageCommand();
    cmd.subject = "my-app";
    assertEquals("my-app", cmd.effectiveSubject("docker.io/library/nginx:latest"));
  }

  /**
   * A failed pull (non-zero oras exit) must fail the run rather than survey stale/empty
   * content.
   */
  @Test
  void failedPullFailsRun() throws Exception {
    SurveyImageCommand cmd = new SurveyImageCommand() {
      @Override
      void pull(String ref, Path layoutDir) throws Exception {
        throw new IllegalArgumentException("Failed to pull image (" + ref + "), oras exited 1");
      }
    };
    cmd.subject = "my-app";
    cmd.image = "nginx";

    int rc = cmd.call();
    assertTrue(rc == 1, "a failed pull must yield a non-zero exit, got " + rc);
  }

  /**
   * The layout directory is cleaned up after the run regardless of outcome.
   */
  @Test
  void layoutDirIsCleanedUp() throws Exception {
    Path base = tempDir.resolve("out");
    Files.createDirectories(base);
    SurveyImageCommand cmd = new SurveyImageCommand() {
      @Override
      void pull(String ref, Path layoutDir) throws Exception {
        Files.createDirectories(layoutDir.resolve("layout.oci"));
      }

      @Override
      int survey(String ref, Path layoutDir) {
        return 0;
      }
    };
    cmd.subject = "my-app";
    cmd.image = "nginx";
    cmd.output = base;

    try (var dirs = Files.list(base)) {
      long before = dirs.filter(p -> p.getFileName().toString().startsWith("image-layout-"))
          .count();
      assertTrue(before == 0, "layout dirs are created fresh, got " + before);
    }

    cmd.run();

    try (var dirs = Files.list(base)) {
      long after = dirs.filter(p -> p.getFileName().toString().startsWith("image-layout-"))
          .count();
      assertTrue(after == 0, "layout dirs must be cleaned up after the run, got " + after);
    }
  }
}
