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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Pull an OCI or Docker image by name and survey its contents.
 *
 * <p>The image is pulled with {@code oras} (baked into the container image) into an OCI
 * image layout, and that layout directory is handed to the same inventory survey machinery
 * as {@code survey inventory} — so upload, cutoff, progress reporting and all the shared
 * options behave identically.
 *
 * <p>Usage:
 *   spice survey image &lt;image&gt; [--subject &lt;label&gt;] [options]
 */
@Command(
    name = "image",
    description = "Survey an OCI or Docker image pulled by name",
    mixinStandardHelpOptions = true,
    footer = {
        "",
        "Examples:",
        "  # Survey the latest nginx image and upload",
        "  spice survey image nginx",
        "",
        "  # Survey a tagged image from a specific registry, skip upload",
        "  spice survey image ghcr.io/spice-labs-inc/grinder:0.1.0 --no-upload",
        "",
        "  # Survey by digest, write output to ./out",
        "  spice survey image ubuntu@sha256:... --no-upload --output ./out",
        "",
        "  # Label the survey instead of using the image name",
        "  spice survey image nginx --subject my-nginx",
        "",
        "The image reference may use any form oras accepts. A bare name such as",
        "'nginx' is expanded to 'docker.io/library/nginx:latest'. SPICE_PASS must be",
        "set in the environment for upload.",
        ""
    }
)
public class SurveyImageCommand implements java.util.concurrent.Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(SurveyImageCommand.class);

  @Parameters(index = "0", description = "OCI or Docker image reference (name[:tag][@digest])")
  String image;

  @Option(
      names = "--subject",
      paramLabel = "LABEL",
      description =
          "Label identifying the system being surveyed (default: the image reference)"
  )
  String subject;

  @Option(names = "--output", description = "Output directory for survey results")
  Path output;

  @Option(names = "--no-upload", description = "Survey only, skip upload")
  boolean noUpload;

  @Option(names = "--tag-json", description = "Additional JSON metadata for tags")
  String tagJson;

  @Option(names = "--threads", description = "Number of threads to use (default: half of available CPU cores)")
  Integer threads;

  @Option(names = "--max-records", description = "Max records to process per batch (default: 5000)")
  int maxRecords = 5000;

  @Option(names = "--chunk-size", description = "Target chunk size in MB for uploads (default: 64)")
  Integer chunkSizeMB;

  @Option(names = "--log-level", description = "Log level: debug|info|warn|error (default: info)")
  String logLevel;

  @Option(names = "--log-file", description = "Path to log file (output appended to both console and file)")
  String logFile;

  @Option(
      names = "--analysis-args",
      description = "Additional analysis args in key=value format",
      split = ","
  )
  List<String> goatRodeoArgsRaw;

  @Option(
      names = "--upload-args",
      description = "Additional upload args in key=value format",
      split = ","
  )
  List<String> gingerArgsRaw;

  @Override
  public Integer call() throws Exception {
    try {
      return run();
    } catch (IllegalArgumentException ex) {
      log.error("❌ {}", ex.getMessage());
      log.info("Use --help for usage information.");
      return 1;
    } catch (Exception ex) {
      log.error("❌ {}", ex.getMessage());
      if (log.isDebugEnabled()) {
        log.error("Stack trace:", ex);
      }
      return 1;
    }
  }

  /**
   * Pull the image and delegate the survey to {@link SurveyInventoryCommand}. The layout
   * is pulled into a temporary directory beside the output base, and removed once the
   * survey and upload have run.
   */
  int run() throws Exception {
    log.info("🌶️  Spice Labs Surveyor CLI v{}", SpiceLabsCLI.VersionProvider.getVersionString());

    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException("No image reference given.");
    }

    String ref = ImageReference.normalize(image);
    log.info("📦 Pulling image {} ...", ref);

    // The layout is transient scratch, deleted once the survey has read it. Put it under
    // --output when given (that path is mounted into the container, so it is writable and
    // keeps large layer pulls off /tmp); otherwise use the JVM temp dir, which always is.
    Path base = output != null ? output : Paths.get(System.getProperty("java.io.tmpdir", "/tmp"));
    Files.createDirectories(base);
    Path layoutDir = Files.createTempDirectory(base, "image-layout-");
    try {
      pull(ref, layoutDir);
      return survey(ref, layoutDir);
    } finally {
      deleteRecursively(layoutDir);
    }
  }

  /**
   * Invoke {@code oras} to copy the image into an OCI image layout under {@code layoutDir}.
   * A non-zero exit aborts the run — an image that could not be pulled must not be silently
   * surveyed as empty or stale content.
   */
  void pull(String ref, Path layoutDir) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(
        "oras", "cp", "--to-oci-layout", ref, layoutDir.resolve("layout.oci").toString());
    pb.redirectErrorStream(true);
    Process proc;
    try {
      proc = pb.start();
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          "Could not start `oras`: " + ex.getMessage()
              + ". The CLI container image must include oras.");
    }
    String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = proc.waitFor();
    if (exit != 0) {
      throw new IllegalArgumentException(
          "Failed to pull image (" + ref + "), oras exited " + exit + ":\n" + output);
    }
  }

  /**
   * The survey label. Defaults to the normalized image reference so {@code survey image
   * nginx} tags the run with {@code docker.io/library/nginx:latest} unless the caller
   * overrides it with {@code --subject}.
   */
  String effectiveSubject(String ref) {
    if (subject != null && !subject.isBlank()) {
      return subject;
    }
    return ref;
  }

  /**
   * Run the shared inventory survey + upload pipeline against the pulled layout directory.
   * The inventory command owns registration, cutoff, progress, and upload; this just points
   * its {@code input} at the OCI layout.
   */
  int survey(String ref, Path layoutDir) throws Exception {
    SurveyInventoryCommand inventory = new SurveyInventoryCommand();
    inventory.subject = effectiveSubject(ref);
    inventory.input = layoutDir.resolve("layout.oci");
    inventory.output = output;
    inventory.noUpload = noUpload;
    inventory.tagJson = tagJson;
    inventory.threads = threads;
    inventory.maxRecords = maxRecords;
    inventory.chunkSizeMB = chunkSizeMB;
    inventory.logLevel = logLevel;
    inventory.logFile = logFile;
    inventory.goatRodeoArgsRaw = goatRodeoArgsRaw;
    inventory.gingerArgsRaw = gingerArgsRaw;
    return inventory.call();
  }

  static void deleteRecursively(Path path) {
    SurveyInventoryCommand.deleteRecursively(path);
  }
}
