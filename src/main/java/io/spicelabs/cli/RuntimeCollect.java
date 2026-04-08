// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            try {
                byte[] config = Ginger.builder().jwt(spicePass).downloadRuntimeConfigBytes();
                if (config != null && config.length > 0) {
                    System.out.write(config);
                    System.out.flush();
                    System.exit(0);
                } else {
                    System.err.println("No probe config returned");
                    System.exit(1);
                }
            } catch (Exception e) {
                System.err.println("Probe config download failed: " + e.getMessage());
                System.exit(1);
            }
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
        log.debug("Found {} recording(s), total size: {}",
                recordings.size(), SurveyRuntimeCommand.humanReadableSize(totalSize));

        // Load probe config if present in the workdir
        Map<String, JfrEventExtractor.ProbeDefinition> probeIndex = null;
        Path probeConfig = dir.resolve("probes.json");
        if (Files.exists(probeConfig)) {
            probeIndex = loadProbeIndex(probeConfig);
            log.debug("Loaded {} probe definitions", probeIndex.size());
        }

        // Parse
        log.debug("Parsing JFR recordings...");
        JfrEventExtractor.RawSurveyData data = JfrEventExtractor.extract(subject, recordings, probeIndex);

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

            log.debug("Uploading survey results...");
            Ginger.builder()
                    .jwt(spicePass)
                    .runtimeSurveyFile(jsonPath)
                    .runtimeSubject(subject)
                    .run();
            log.debug("Upload complete.");
        } else {
            log.debug("--no-upload: remove to send results to Spice Labs for full categorization.");
        }
    }

    @SuppressWarnings("unchecked")
    /** Sanitize a JFR event name so each dot-separated segment is a valid Java identifier. */
    private static String sanitizeJfrName(String name) {
        String[] parts = name.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('.');
            String part = parts[i];
            if (!part.isEmpty() && Character.isDigit(part.charAt(0))) {
                sb.append('p').append(part);
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private static Map<String, JfrEventExtractor.ProbeDefinition> loadProbeIndex(Path configPath) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> config = mapper.readValue(configPath.toFile(), Map.class);
            List<Map<String, String>> probes = (List<Map<String, String>>) config.get("probes");
            if (probes == null) return Map.of();
            Map<String, JfrEventExtractor.ProbeDefinition> index = new HashMap<>();
            for (Map<String, String> p : probes) {
                String id = p.get("id");
                if (id != null) {
                    JfrEventExtractor.ProbeDefinition def = new JfrEventExtractor.ProbeDefinition(
                            id, p.get("class"), p.get("method"), p.get("label"));
                    // Index by both raw ID and sanitized JFR name so lookup works
                    // regardless of whether the event name was sanitized by ancho
                    index.put(id, def);
                    String sanitized = sanitizeJfrName(id);
                    if (!sanitized.equals(id)) {
                        index.put(sanitized, def);
                    }
                }
            }
            return index;
        } catch (Exception e) {
            log.warn("Failed to load probe config: {}", e.getMessage());
            return Map.of();
        }
    }
}
