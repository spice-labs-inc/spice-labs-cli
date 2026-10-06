// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

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

  /** Without {@code --subject}, the survey is tagged with the repository name, whatever the tag. */
  @Test
  void subjectDefaultsToTheRepositoryName() {
    SurveyImageCommand cmd = new SurveyImageCommand();
    cmd.image = "nginx";
    assertEquals("nginx", cmd.effectiveSubject("docker.io/library/nginx:latest"));
    assertEquals("ghcr.io/acme/web", cmd.effectiveSubject("ghcr.io/acme/web:2.4.1"));
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

  /**
   * {@code survey inventory <subject> docker://<image>} runs this survey: the same pull, the
   * inventory command's subject and options, and no alias notice.
   */
  @Test
  void inventoryWithDockerInputRunsTheImageSurvey() throws Exception {
    String[] pulled = new String[1];
    SurveyImageCommand[] used = new SurveyImageCommand[1];
    SurveyInventoryCommand inventory = new SurveyInventoryCommand() {
      @Override
      SurveyImageCommand newImageCommand() {
        used[0] = new SurveyImageCommand() {
          @Override
          void pull(String ref, Path layoutDir) throws Exception {
            pulled[0] = ref;
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
    inventory.subject = "my-app";
    inventory.source = new SurveyInput("docker://nginx:1.27");
    inventory.output = tempDir;
    inventory.noUpload = true;
    inventory.threads = 3;

    PrintStream savedErr = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    int rc;
    try {
      rc = inventory.call();
    } finally {
      System.setErr(savedErr);
    }

    assertEquals(0, rc);
    assertEquals("docker.io/library/nginx:1.27", pulled[0]);
    assertEquals("my-app", used[0].effectiveSubject(pulled[0]));
    assertEquals(3, used[0].threads);
    assertTrue(used[0].noUpload);
    assertEquals(tempDir, used[0].output);
    assertNull(used[0].maxRecords, "an unset --max-records leaves the configured value in charge");
    assertFalse(err.toString(StandardCharsets.UTF_8).contains("will be removed"), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void inventoryUploadOnlyWithAnImageIsRefusedWithoutPulling() throws Exception {
    SurveyInventoryCommand inventory = new SurveyInventoryCommand() {
      @Override
      SurveyImageCommand newImageCommand() {
        throw new AssertionError("nothing may be pulled");
      }
    };
    inventory.subject = "my-app";
    inventory.source = new SurveyInput("docker://nginx:1.27");
    inventory.uploadOnly = true;
    assertEquals(1, inventory.call());
  }

  @Test
  void typedDirectlyTheAliasNamesTheCommandThatReplacesIt() {
    SurveyImageCommand cmd = new SurveyImageCommand();
    cmd.image = "nginx:1.27";
    assertEquals("Note: spice survey image will be removed. Use: spice survey inventory docker://nginx:1.27",
        cmd.replacementNotice());
    cmd.subject = "my-nginx";
    assertEquals("Note: spice survey image will be removed. Use: spice survey inventory my-nginx docker://nginx:1.27",
        cmd.replacementNotice());
  }

  @Test
  void theAliasPrintsItsNoticeOnStderrOnce() throws Exception {
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
    cmd.image = "nginx";
    cmd.output = tempDir;
    PrintStream savedErr = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      assertEquals(0, cmd.call());
    } finally {
      System.setErr(savedErr);
    }
    String printed = err.toString(StandardCharsets.UTF_8);
    assertEquals(1, printed.lines().filter(l -> l.contains("will be removed")).count(), printed);
    assertTrue(printed.contains("Use: spice survey inventory docker://nginx"), printed);
  }

  @Test
  void theAliasIsHiddenFromHelp() {
    CommandLine root = SpiceLabsCLI.newCommandLine();
    CommandLine survey = root.getSubcommands().get("survey");
    assertTrue(survey.getSubcommands().containsKey("image"), "the alias still runs");
    assertTrue(survey.getSubcommands().get("image").getCommandSpec().usageMessage().hidden());
    String help = survey.getUsageMessage();
    assertFalse(help.lines().anyMatch(l -> l.trim().startsWith("image")), help);
    assertTrue(help.contains("docker://nginx:1.27"), help);
  }

  @Test
  void inventoryWithOciInputRunsTheSameImageSurvey() throws Exception {
    String[] pulled = new String[1];
    SurveyInventoryCommand inventory = new SurveyInventoryCommand() {
      @Override
      SurveyImageCommand newImageCommand() {
        return new SurveyImageCommand() {
          @Override
          void pull(String ref, Path layoutDir) throws Exception {
            pulled[0] = ref;
            Files.createDirectories(layoutDir.resolve("layout.oci"));
          }

          @Override
          int survey(String ref, Path layoutDir) {
            return 0;
          }
        };
      }
    };
    inventory.subject = "my-app";
    inventory.source = new SurveyInput("oci://ghcr.io/acme/app:1.0");
    inventory.output = tempDir;
    inventory.noUpload = true;

    assertEquals(0, inventory.call());
    assertEquals("ghcr.io/acme/app:1.0", pulled[0]);
  }
}
