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
            List<SecurityProperty> securityProperties,
            List<LoadedClass> loadedClasses,
            Anchor anchor
    ) {
        public static Builder builder() {
            return new Builder();
        }

        /** This survey's fields as a builder, for adding or overriding one field. */
        public Builder toBuilder() {
            return builder()
                    .version(version).type(type).subject(subject).runtime(runtime)
                    .recordings(recordings).probeEvents(probeEvents)
                    .securityProviderEvents(securityProviderEvents).tlsHandshakes(tlsHandshakes)
                    .certificates(certificates).securityProperties(securityProperties)
                    .loadedClasses(loadedClasses).anchor(anchor);
        }

        /** Builder so callers don't pass a dozen positional args. */
        public static final class Builder {
            private String version;
            private String type;
            private String subject;
            private RuntimeInfo runtime;
            private List<String> recordings;
            private List<ProbeEvent> probeEvents;
            private List<SecurityProviderEvent> securityProviderEvents;
            private List<TlsHandshake> tlsHandshakes;
            private List<CertificateRecord> certificates;
            private List<SecurityProperty> securityProperties;
            private List<LoadedClass> loadedClasses;
            private Anchor anchor;

            public Builder version(String v) { this.version = v; return this; }
            public Builder type(String v) { this.type = v; return this; }
            public Builder subject(String v) { this.subject = v; return this; }
            public Builder runtime(RuntimeInfo v) { this.runtime = v; return this; }
            public Builder recordings(List<String> v) { this.recordings = v; return this; }
            public Builder probeEvents(List<ProbeEvent> v) { this.probeEvents = v; return this; }
            public Builder securityProviderEvents(List<SecurityProviderEvent> v) { this.securityProviderEvents = v; return this; }
            public Builder tlsHandshakes(List<TlsHandshake> v) { this.tlsHandshakes = v; return this; }
            public Builder certificates(List<CertificateRecord> v) { this.certificates = v; return this; }
            public Builder securityProperties(List<SecurityProperty> v) { this.securityProperties = v; return this; }
            public Builder loadedClasses(List<LoadedClass> v) { this.loadedClasses = v; return this; }
            public Builder anchor(Anchor v) { this.anchor = v; return this; }

            public RawSurveyData build() {
                return new RawSurveyData(version, type, subject, runtime, recordings, probeEvents,
                        securityProviderEvents, tlsHandshakes, certificates, securityProperties,
                        loadedClasses, anchor);
            }
        }
    }

    /**
     * The build artifact (jar/war/ear) this survey is of, identified by content hash. {@code sha256}
     * and {@code gitoid} match goatrodeo's inventory hashes so a CBOM can correlate this survey with
     * the inventory ADG and other surveys. {@code path} is the file name, for display only.
     */
    public record Anchor(String path, String sha256, String gitoid) {}

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
            List<CallSite> callSites,
            Integer classId,
            List<Integer> callerClassIds
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

    public record CallSite(String className, String location, String thread) {}

    /**
     * A class loaded at runtime, hashed identically to goatrodeo's inventory hashes so the two
     * can be correlated. {@code classGitoid}/{@code classSha256} match the inventory ADG node's
     * primary id / {@code sha256:} alias; {@code jarGitoid}/{@code jarSha256} likewise match the
     * originating jar (absent for exploded dirs and nested fat-jar entries). {@code id} is a stable
     * per-survey index, referenced by later per-event class linking.
     */
    public record LoadedClass(
            int id,
            String className,
            String classGitoid,
            String classSha256,
            String codeSource,
            String jarGitoid,
            String jarSha256
    ) {}

    // ── Internal accumulators ───────────────────────────────────────────

    public record ProbeDefinition(String id, String classFqn, String method, String label) {}

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
        // Class linking: declaring-class gitoid (constant per probe) + caller-class gitoids.
        String classGitoid;
        final Set<String> callerGitoids = new LinkedHashSet<>();

        ProbeAccumulator(String eventType, String classFqn, String methodName, String probeLabel) {
            this.eventType = eventType;
            this.classFqn = classFqn;
            this.methodName = methodName;
            this.probeLabel = probeLabel;
        }

        void addCallSite(String className, String location, String thread) {
            String key = location + "|" + (thread != null ? thread : "");
            if (seenSites.add(key)) {
                callSites.add(new CallSite(className, location, thread));
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

        void addCallSite(String className, String location, String thread) {
            String key = location + "|" + (thread != null ? thread : "");
            if (seenSites.add(key)) {
                callSites.add(new CallSite(className, location, thread));
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
        return extract(subject, recordingPaths, null, null);
    }

    public static RawSurveyData extract(String subject, List<Path> recordingPaths, Map<String, ProbeDefinition> probeIndex) throws Exception {
        return extract(subject, recordingPaths, probeIndex, null);
    }

    public static RawSurveyData extract(String subject, List<Path> recordingPaths, Map<String, ProbeDefinition> probeIndex,
                                        JfrProgressCallback progress) throws Exception {
        if (recordingPaths == null || recordingPaths.isEmpty()) {
            throw new IllegalArgumentException("No recording files provided");
        }

        Map<ProbeKey, ProbeAccumulator> probeMap = new LinkedHashMap<>();
        Map<SecurityProviderKey, SecurityProviderAccumulator> secProvMap = new LinkedHashMap<>();
        Map<TlsKey, long[]> tlsMap = new LinkedHashMap<>();
        Map<String, CertificateRecord> certMap = new LinkedHashMap<>();
        Map<String, SecurityProperty> secPropMap = new LinkedHashMap<>();
        Map<String, LoadedClass> classLoadMap = new LinkedHashMap<>();
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

        int totalRecordings = recordingPaths.size();
        int recordingIndex = 0;
        // Liveness ticks within a single recording — same (current, total), updated_at advances.
        // Goat-rodeo uses the same rule (every 1k items or 30s); we use 1s here because
        // JFR events stream much faster than goat-rodeo's per-artifact processing.
        long eventsSinceTick = 0L;
        long lastTickMillis = System.currentTimeMillis();
        final long EVENT_TICK_INTERVAL = 1000L;
        final long TIME_TICK_INTERVAL_MS = 1000L;
        if (progress != null) {
            progress.onProgress(0, totalRecordings);
        }

        for (Path recording : recordingPaths) {
            recordingNames.add(recording.getFileName().toString());
            log.info("Parsing recording {} of {}: {}", recordingIndex + 1, totalRecordings, recording.getFileName());

            try (RecordingFile rf = new RecordingFile(recording)) {
                while (rf.hasMoreEvents()) {
                    RecordedEvent event = rf.readEvent();
                    if (progress != null) {
                        eventsSinceTick++;
                        long now = System.currentTimeMillis();
                        if (eventsSinceTick >= EVENT_TICK_INTERVAL || (now - lastTickMillis) >= TIME_TICK_INTERVAL_MS) {
                            progress.onProgress(recordingIndex, totalRecordings);
                            eventsSinceTick = 0;
                            lastTickMillis = now;
                        }
                    }
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

                        // Must be a dedicated case: the default arm only handles "spice.probe.*",
                        // so spice.ClassLoaded would otherwise be dropped.
                        case "spice.ClassLoaded" -> {
                            String classGitoid = event.getString("classGitoid");
                            if (classGitoid != null) {
                                classLoadMap.putIfAbsent(classGitoid, new LoadedClass(
                                        0, // id assigned at materialization
                                        event.getString("className"),
                                        classGitoid,
                                        event.getString("classSha256"),
                                        event.getString("codeSource"),
                                        event.getString("jarGitoid"),
                                        event.getString("jarSha256")));
                            }
                        }

                        default -> {
                            // Spice probe events
                            if (eventType.startsWith("spice.probe.")) {
                                processProbeEvent(event, eventType, probeMap, probeIndex);
                            }
                        }
                    }
                }
            }

            totalDistinctEvents = probeMap.size() + secProvMap.size() + classLoadMap.size();
            if (totalDistinctEvents > MAX_DISTINCT_EVENTS && !truncated) {
                log.warn("⚠️  {} distinct events exceed cap of {}. Results may be truncated.",
                        totalDistinctEvents, MAX_DISTINCT_EVENTS);
                truncated = true;
            }

            recordingIndex++;
            if (progress != null) {
                progress.onProgress(recordingIndex, totalRecordings);
                eventsSinceTick = 0L;
                lastTickMillis = System.currentTimeMillis();
            }
        }

        RuntimeInfo runtime = new RuntimeInfo(
                jvmVersion[0], jvmName[0], jvmVendor[0], javaVersion[0], os[0], pid[0]);

        // Build loadedClasses first (with stable ids) + a gitoid -> id index for event linking.
        List<LoadedClass> loadedClasses = new ArrayList<>(classLoadMap.size());
        Map<String, Integer> gitoidToId = new LinkedHashMap<>();
        int loadedClassId = 0;
        for (LoadedClass lc : classLoadMap.values()) {
            loadedClasses.add(new LoadedClass(loadedClassId, lc.className(), lc.classGitoid(),
                    lc.classSha256(), lc.codeSource(), lc.jarGitoid(), lc.jarSha256()));
            gitoidToId.put(lc.classGitoid(), loadedClassId);
            loadedClassId++;
        }

        List<ProbeEvent> probeEvents = probeMap.values().stream()
                .map(a -> new ProbeEvent(a.eventType, a.classFqn, a.methodName, a.probeLabel, a.count,
                        a.callSites,
                        gitoidToId.get(a.classGitoid),
                        resolveClassIds(a.callerGitoids, gitoidToId)))
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

        log.info("Extracted: {} probe events, {} security provider events, {} TLS handshakes, {} certs, {} security properties, {} loaded classes from {} recording(s)",
                probeEvents.size(), secProvEvents.size(), tlsHandshakes.size(),
                certificates.size(), securityProperties.size(), loadedClasses.size(), recordingPaths.size());

        return RawSurveyData.builder()
                .version("1.0.0")
                .type("runtime-pqc-survey")
                .subject(subject)
                .runtime(runtime)
                .recordings(recordingNames)
                .probeEvents(probeEvents)
                .securityProviderEvents(secProvEvents)
                .tlsHandshakes(tlsHandshakes)
                .certificates(certificates)
                .securityProperties(securityProperties)
                .loadedClasses(loadedClasses)
                .build();
    }

    // ── Probe event processing ──────────────────────────────────────────

    private static void processProbeEvent(RecordedEvent event, String eventType,
                                          Map<ProbeKey, ProbeAccumulator> probeMap,
                                          Map<String, ProbeDefinition> probeIndex) {
        String probeLabel = null;
        try {
            probeLabel = event.getEventType().getLabel();
        } catch (Exception ignored) {}

        // Resolve class/method from probe config (authoritative) or fall back to stack trace
        String classFqn = null;
        String methodName = null;
        if (probeIndex != null) {
            ProbeDefinition def = probeIndex.get(eventType);
            if (def != null) {
                classFqn = def.classFqn();
                methodName = def.method();
                if (probeLabel == null) probeLabel = def.label();
            }
        }
        final String label = probeLabel;

        // Fall back to stack trace if probe config didn't resolve
        RecordedStackTrace st = event.getStackTrace();
        if (classFqn == null && st != null && !st.getFrames().isEmpty()) {
            RecordedFrame topFrame = st.getFrames().get(0);
            classFqn = topFrame.getMethod().getType().getName();
            methodName = topFrame.getMethod().getName();
        }

        if (classFqn == null) {
            classFqn = eventType;
            methodName = "unknown";
        }

        final String finalClassFqn = classFqn;
        final String finalMethodName = methodName;
        var key = new ProbeKey(finalClassFqn, finalMethodName);
        var acc = probeMap.computeIfAbsent(key,
                k -> new ProbeAccumulator(eventType, finalClassFqn, finalMethodName, label));
        acc.count++;

        // Declaring-class + caller gitoids stamped by the agent (absent on older agents).
        if (event.hasField("classGitoid")) {
            String classGitoid = event.getString("classGitoid");
            if (classGitoid != null) {
                acc.classGitoid = classGitoid;
            }
        }
        if (event.hasField("callerGitoids")) {
            String callers = event.getString("callerGitoids");
            if (callers != null && !callers.isEmpty()) {
                for (String g : callers.split("\n")) {
                    if (!g.isEmpty()) {
                        acc.callerGitoids.add(g);
                    }
                }
            }
        }

        // Add application-level call site (skip JDK/agent frames)
        if (st != null) {
            String thread = event.getThread() != null ? event.getThread().getJavaName() : null;
            for (RecordedFrame frame : st.getFrames()) {
                String cn = frame.getMethod().getType().getName();
                if (isApplicationCode(cn)) {
                    String site = cn + "." + frame.getMethod().getName() + "() line " + frame.getLineNumber();
                    acc.addCallSite(cn, site, thread);
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
                acc.addCallSite(cn, site, thread);
                break;
            }
        }
    }

    /** Resolve a set of caller gitoids to compact loadedClasses ids, dropping any not present. */
    private static List<Integer> resolveClassIds(Set<String> gitoids, Map<String, Integer> gitoidToId) {
        if (gitoids == null || gitoids.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (String gitoid : gitoids) {
            Integer id = gitoidToId.get(gitoid);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
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
