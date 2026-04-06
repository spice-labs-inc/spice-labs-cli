// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.spicelabs.ginger.Ginger;

/**
 * Internal entry point used by the wrapper script to parse JFR recordings
 * and upload results. Not a user-facing command.
 *
 * <p>Usage: java -cp spice-labs-cli.jar io.spicelabs.cli.RuntimeCollect &lt;subject&gt; &lt;dir&gt; [--no-upload]
 */
public class RuntimeCollect {

    private static final Logger log = LoggerFactory.getLogger(RuntimeCollect.class);

    public static void main(String[] args) throws Exception {
        // Probe config download mode: streams JSON to stdout, no file written
        if (args.length >= 1 && "--download-probes".equals(args[0])) {
            String spicePass = System.getenv("SPICE_PASS");
            if (spicePass == null || spicePass.isBlank()) {
                System.exit(1);
            }
            byte[] config = Ginger.builder().jwt(spicePass).downloadRuntimeConfigBytes();
            if (config != null) {
                System.out.write(config);
                System.out.flush();
            }
            System.exit(config != null ? 0 : 1);
        }

        if (args.length < 2) {
            System.err.println("Usage: RuntimeCollect <subject> <dir> [--no-upload]");
            System.exit(1);
        }

        String subject = args[0];
        Path dir = Path.of(args[1]);
        boolean noUpload = args.length > 2 && "--no-upload".equals(args[2]);

        if (!Files.isDirectory(dir)) {
            log.error("Directory not found: {}", dir);
            System.exit(1);
        }

        // Collect .jfr files
        List<Path> recordings;
        try (Stream<Path> files = Files.list(dir)) {
            recordings = files
                    .filter(p -> p.toString().endsWith(".jfr"))
                    .filter(Files::isRegularFile)
                    .toList();
        }

        if (recordings.isEmpty()) {
            log.error("No JFR recordings found in {}", dir);
            System.exit(1);
        }

        long totalSize = recordings.stream().mapToLong(p -> {
            try { return Files.size(p); } catch (IOException e) { return 0; }
        }).sum();
        log.info("Found {} recording(s), total size: {}",
                recordings.size(), SurveyRuntimeCommand.humanReadableSize(totalSize));

        // Parse
        log.info("Parsing JFR recordings...");
        JfrEventExtractor.RawSurveyData data = JfrEventExtractor.extract(subject, recordings);

        // Print summary
        new SurveyRuntimeCommand().printSummary(data);

        // Upload
        if (!noUpload) {
            String spicePass = System.getenv("SPICE_PASS");
            if (spicePass == null || spicePass.isBlank()) {
                log.error("SPICE_PASS not set");
                System.exit(1);
            }

            Path jsonPath = dir.resolve("survey-data.json");
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.writeValue(jsonPath.toFile(), data);

            log.info("Uploading survey results...");
            Ginger.builder()
                    .jwt(spicePass)
                    .runtimeSurveyFile(jsonPath)
                    .run();
            log.info("Upload complete.");
        } else {
            log.info("--no-upload: remove to send results to Spice Labs for full categorization.");
        }
    }
}
