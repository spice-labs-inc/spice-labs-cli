// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Extracts raw JFR events from one or more recording files.
 *
 * <p>This is the client-side parser — it extracts structured event data without
 * any categorization. All categorization happens server-side in fennel using the
 * rogues gallery.
 *
 * <p>Simplified from {@code JfrAnalyzer.java} in amuse-bouche and
 * {@code ProbeReport.java} in caraway/docs/jfr-demo.
 */
public class JfrEventExtractor {

    private static final Logger log = LoggerFactory.getLogger(JfrEventExtractor.class);

    /** Maximum distinct events before truncation warning. */
    private static final int MAX_DISTINCT_EVENTS = 100_000;

    // ── Data model (raw, no categorization) ─────────────────────────────

    public record RawSurveyData(
            String version,
            String type,
            String subject,
            RuntimeInfo runtime,
            List<String> recordings,
            List<ProbeEvent> probeEvents,
            List<SecurityProviderEvent> securityProviderEvents,
            List<TlsHandshake> tlsHandshakes,
            List<CertificateRecord> certificates,
            List<SecurityProperty> securityProperties
    ) {}

    public record RuntimeInfo(
            String jvmVersion,
            String jvmName,
            String jvmVendor,
            String javaVersion,
            String os,
            long pid
    ) {}

    public record ProbeEvent(
            String eventType,
            String classFqn,
            String methodName,
            String probeLabel,
            long count,
            List<CallSite> callSites
    ) {}

    public record SecurityProviderEvent(
            String algorithm,
            String serviceType,
            long count,
            List<CallSite> callSites
    ) {}

    public record TlsHandshake(
            String peerHost,
            int peerPort,
            String protocol,
            String cipherSuite,
            long count
    ) {}

    public record CertificateRecord(
            String subject,
            String issuer,
            String keyType,
            int keyLength,
            String sigAlgo,
            String validFrom,
            String validUntil
    ) {}

    public record SecurityProperty(
            String key,
            String value,
            boolean modified
    ) {}

    public record CallSite(String location, String thread) {}

    // ── Internal accumulators ───────────────────────────────────────────

    private record ProbeKey(String classFqn, String methodName) {}

    private record SecurityProviderKey(String algorithm, String serviceType) {}

    private record TlsKey(String peerHost, int peerPort, String protocol, String cipherSuite) {}

    private static class ProbeAccumulator {
        final String eventType;
        final String classFqn;
        final String methodName;
        final String probeLabel;
        long count;
        final List<CallSite> callSites = new ArrayList<>();
        final Set<String> seenSites = new LinkedHashSet<>();

        ProbeAccumulator(String eventType, String classFqn, String methodName, String probeLabel) {
            this.eventType = eventType;
            this.classFqn = classFqn;
            this.methodName = methodName;
            this.probeLabel = probeLabel;
        }

        void addCallSite(String location, String thread) {
            String key = location + "|" + (thread != null ? thread : "");
            if (seenSites.add(key)) {
                callSites.add(new CallSite(location, thread));
            }
        }
    }

    private static class SecurityProviderAccumulator {
        final String algorithm;
        final String serviceType;
        long count;
        final List<CallSite> callSites = new ArrayList<>();
        final Set<String> seenSites = new LinkedHashSet<>();

        SecurityProviderAccumulator(String algorithm, String serviceType) {
            this.algorithm = algorithm;
            this.serviceType = serviceType;
        }

        void addCallSite(String location, String thread) {
            String key = location + "|" + (thread != null ? thread : "");
            if (seenSites.add(key)) {
                callSites.add(new CallSite(location, thread));
            }
        }
    }

    // ── Main extraction ─────────────────────────────────────────────────

    /**
     * Parse one or more JFR recording files and extract raw events.
     * No categorization — just structured event data.
     *
     * @param subject label identifying the system being surveyed
     * @param recordingPaths paths to JFR recording files
     * @return raw survey data ready for JSON serialization and upload
     */
    public static RawSurveyData extract(String subject, List<Path> recordingPaths) throws Exception {
        if (recordingPaths == null || recordingPaths.isEmpty()) {
            throw new IllegalArgumentException("No recording files provided");
        }

        Map<ProbeKey, ProbeAccumulator> probeMap = new LinkedHashMap<>();
        Map<SecurityProviderKey, SecurityProviderAccumulator> secProvMap = new LinkedHashMap<>();
        Map<TlsKey, long[]> tlsMap = new LinkedHashMap<>();
        Map<String, CertificateRecord> certMap = new LinkedHashMap<>();
        Map<String, SecurityProperty> secPropMap = new LinkedHashMap<>();
        List<String> recordingNames = new ArrayList<>();

        // Runtime info — keep from first recording that has it
        String[] jvmVersion = {null};
        String[] jvmName = {null};
        String[] jvmVendor = {null};
        String[] javaVersion = {null};
        String[] os = {null};
        long[] pid = {0};

        long totalDistinctEvents = 0;
        boolean truncated = false;

        for (Path recording : recordingPaths) {
            recordingNames.add(recording.getFileName().toString());
            log.info("Parsing recording: {}", recording.getFileName());

            try (RecordingFile rf = new RecordingFile(recording)) {
                while (rf.hasMoreEvents()) {
                    RecordedEvent event = rf.readEvent();
                    String eventType = event.getEventType().getName();

                    switch (eventType) {
                        case "jdk.SecurityProviderService" -> {
                            String algo = event.getString("algorithm");
                            String svcType = event.getString("type");
                            if (algo != null && svcType != null) {
                                var key = new SecurityProviderKey(algo, svcType);
                                var acc = secProvMap.computeIfAbsent(key,
                                        k -> new SecurityProviderAccumulator(algo, svcType));
                                acc.count++;
                                addApplicationStackTrace(acc, event);
                            }
                        }

                        case "jdk.TLSHandshake" -> {
                            String proto = event.getString("protocolVersion");
                            String suite = event.getString("cipherSuite");
                            String peer = event.getString("peerHost");
                            int port = event.getInt("peerPort");
                            var key = new TlsKey(peer, port, proto, suite);
                            tlsMap.computeIfAbsent(key, k -> new long[]{0})[0]++;
                        }

                        case "jdk.X509Certificate" -> {
                            String certSubject = event.getString("subject");
                            if (certSubject != null && !certMap.containsKey(certSubject)) {
                                certMap.put(certSubject, new CertificateRecord(
                                        certSubject,
                                        event.getString("issuer"),
                                        event.getString("keyType"),
                                        event.getInt("keyLength"),
                                        event.getString("algorithm"),
                                        safeInstantString(event, "validFrom"),
                                        safeInstantString(event, "validUntil")
                                ));
                            }
                        }

                        case "jdk.SecurityPropertyModification" -> {
                            String propKey = event.getString("key");
                            if (propKey != null) {
                                secPropMap.put(propKey, new SecurityProperty(
                                        propKey, event.getString("value"), true));
                            }
                        }

                        case "jdk.InitialSecurityProperty" -> {
                            String propKey = event.getString("key");
                            if (propKey != null) {
                                secPropMap.putIfAbsent(propKey, new SecurityProperty(
                                        propKey, event.getString("value"), false));
                            }
                        }

                        case "jdk.JVMInformation" -> {
                            if (jvmVersion[0] == null) {
                                jvmVersion[0] = event.getString("jvmVersion");
                                jvmName[0] = event.getString("jvmName");
                                pid[0] = event.getLong("pid");
                            }
                        }

                        case "jdk.OSInformation" -> {
                            if (os[0] == null) {
                                String raw = event.getString("osVersion");
                                if (raw != null) {
                                    for (String line : raw.split("\n")) {
                                        if (line.startsWith("uname:")) {
                                            raw = line.substring(6).trim();
                                            break;
                                        }
                                    }
                                }
                                os[0] = raw;
                            }
                        }

                        case "jdk.InitialSystemProperty" -> {
                            String k = event.getString("key");
                            String v = event.getString("value");
                            if ("java.vm.vendor".equals(k) && jvmVendor[0] == null) jvmVendor[0] = v;
                            else if ("java.vm.specification.version".equals(k) && javaVersion[0] == null) javaVersion[0] = v;
                        }

                        default -> {
                            // Spice probe events
                            if (eventType.startsWith("spice.probe.")) {
                                processProbeEvent(event, eventType, probeMap);
                            }
                        }
                    }
                }
            }

            totalDistinctEvents = probeMap.size() + secProvMap.size();
            if (totalDistinctEvents > MAX_DISTINCT_EVENTS && !truncated) {
                log.warn("⚠️  {} distinct events exceed cap of {}. Results may be truncated.",
                        totalDistinctEvents, MAX_DISTINCT_EVENTS);
                truncated = true;
            }
        }

        RuntimeInfo runtime = new RuntimeInfo(
                jvmVersion[0], jvmName[0], jvmVendor[0], javaVersion[0], os[0], pid[0]);

        List<ProbeEvent> probeEvents = probeMap.values().stream()
                .map(a -> new ProbeEvent(a.eventType, a.classFqn, a.methodName, a.probeLabel, a.count, a.callSites))
                .toList();

        List<SecurityProviderEvent> secProvEvents = secProvMap.values().stream()
                .map(a -> new SecurityProviderEvent(a.algorithm, a.serviceType, a.count, a.callSites))
                .toList();

        List<TlsHandshake> tlsHandshakes = tlsMap.entrySet().stream()
                .map(e -> new TlsHandshake(e.getKey().peerHost(), e.getKey().peerPort(),
                        e.getKey().protocol(), e.getKey().cipherSuite(), e.getValue()[0]))
                .toList();

        List<CertificateRecord> certificates = new ArrayList<>(certMap.values());
        List<SecurityProperty> securityProperties = new ArrayList<>(secPropMap.values());

        log.info("Extracted: {} probe events, {} security provider events, {} TLS handshakes, {} certs, {} security properties from {} recording(s)",
                probeEvents.size(), secProvEvents.size(), tlsHandshakes.size(),
                certificates.size(), securityProperties.size(), recordingPaths.size());

        return new RawSurveyData(
                "1.0.0",
                "runtime-pqc-survey",
                subject,
                runtime,
                recordingNames,
                probeEvents,
                secProvEvents,
                tlsHandshakes,
                certificates,
                securityProperties
        );
    }

    // ── Probe event processing ──────────────────────────────────────────

    private static void processProbeEvent(RecordedEvent event, String eventType,
                                          Map<ProbeKey, ProbeAccumulator> probeMap) {
        // Extract class and method from the event type name
        // Event type format: spice.probe.<hash>
        // The actual class/method info is in the event fields or stack trace
        String probeLabel = null;
        try {
            probeLabel = event.getEventType().getLabel();
        } catch (Exception ignored) {}
        final String label = probeLabel;

        // Try to get class/method from stack trace (the instrumented method is the first frame)
        String classFqn = null;
        String methodName = null;
        RecordedStackTrace st = event.getStackTrace();
        if (st != null && !st.getFrames().isEmpty()) {
            RecordedFrame topFrame = st.getFrames().get(0);
            classFqn = topFrame.getMethod().getType().getName();
            methodName = topFrame.getMethod().getName();
        }

        if (classFqn == null) {
            // Fallback: use event type as identifier
            classFqn = eventType;
            methodName = "unknown";
        }

        final String finalClassFqn = classFqn;
        final String finalMethodName = methodName;
        var key = new ProbeKey(finalClassFqn, finalMethodName);
        var acc = probeMap.computeIfAbsent(key,
                k -> new ProbeAccumulator(eventType, finalClassFqn, finalMethodName, label));
        acc.count++;

        // Add application-level call site (skip JDK/agent frames)
        if (st != null) {
            String thread = event.getThread() != null ? event.getThread().getJavaName() : null;
            for (RecordedFrame frame : st.getFrames()) {
                String cn = frame.getMethod().getType().getName();
                if (isApplicationCode(cn)) {
                    String site = cn + "." + frame.getMethod().getName() + "() line " + frame.getLineNumber();
                    acc.addCallSite(site, thread);
                    break;
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static void addApplicationStackTrace(SecurityProviderAccumulator acc, RecordedEvent event) {
        RecordedStackTrace st = event.getStackTrace();
        if (st == null) return;
        String thread = event.getThread() != null ? event.getThread().getJavaName() : null;
        for (RecordedFrame frame : st.getFrames()) {
            String cn = frame.getMethod().getType().getName();
            if (isApplicationCode(cn)) {
                String site = cn + "." + frame.getMethod().getName() + "() line " + frame.getLineNumber();
                acc.addCallSite(site, thread);
                break;
            }
        }
    }

    static boolean isApplicationCode(String className) {
        return !className.startsWith("java.")
                && !className.startsWith("javax.")
                && !className.startsWith("sun.")
                && !className.startsWith("com.sun.")
                && !className.startsWith("jdk.")
                && !className.startsWith("org.openjdk.");
    }

    private static String safeInstantString(RecordedEvent event, String field) {
        try {
            var instant = event.getInstant(field);
            return instant != null ? instant.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
