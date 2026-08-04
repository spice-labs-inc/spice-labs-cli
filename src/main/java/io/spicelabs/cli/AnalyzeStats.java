// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Completeness counters for one ANALYZE pass, published on the terminal status POST so the
 * server can detect silent truncation (an analyzer that exits 0 having seen only part of
 * the input).
 *
 * <p>Two independently-sourced sides, deliberately in different units:
 * <ul>
 *   <li><b>Encountered side</b> ({@code filesEncountered}/{@code bytesEncountered}/
 *       {@code filesUnreadable}) — a cheap stat-only walk of the input tree done by the CLI
 *       itself before goat-rodeo runs. Units are filesystem files.</li>
 *   <li><b>Processed side</b> ({@code itemsSeen}/{@code itemsProcessed}) — the last
 *       {@code (total, current)} tick observed from goat-rodeo's ProgressListener. Units are
 *       goat-rodeo items, which include entries nested inside archives, so these are NOT
 *       comparable 1:1 with the file counts. They are also lower bounds: the listener is
 *       throttled, so the final tick can undercount slightly.</li>
 * </ul>
 *
 * <p>Fields whose value is unknown are omitted from the payload, never reported as zero.
 */
final class AnalyzeStats {

  private static final Logger log = LoggerFactory.getLogger(AnalyzeStats.class);

  private long filesEncountered;
  private long bytesEncountered;
  private long filesUnreadable;
  private final Map<String, Long> unreadableReasons = new TreeMap<>();
  private boolean scanned;

  private long itemsSeen = -1;
  private long itemsProcessed = -1;

  /**
   * Walk the input tree counting regular files, their bytes, and unreadable entries.
   * Best-effort: any failure leaves the encountered side unreported rather than partial.
   */
  static AnalyzeStats scanInput(Path input) {
    AnalyzeStats stats = new AnalyzeStats();
    try {
      Files.walkFileTree(input, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.isRegularFile()) {
            stats.filesEncountered++;
            stats.bytesEncountered += attrs.size();
            if (!Files.isReadable(file)) {
              stats.filesUnreadable++;
              stats.unreadableReasons.merge("permission_denied", 1L, Long::sum);
            }
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          stats.filesUnreadable++;
          stats.unreadableReasons.merge("walk_error", 1L, Long::sum);
          return FileVisitResult.CONTINUE;
        }
      });
      stats.scanned = true;
      log.debug("Input scan: {} files, {} bytes, {} unreadable",
          stats.filesEncountered, stats.bytesEncountered, stats.filesUnreadable);
    } catch (Exception e) {
      log.debug("Input scan failed; analyzeStats will omit encountered counts: {}", e.getMessage());
    }
    return stats;
  }

  /** Record a goat-rodeo progress tick. {@code current} is monotonic; {@code total} may grow. */
  synchronized void recordProgress(long current, long total) {
    itemsProcessed = Math.max(itemsProcessed, current);
    itemsSeen = Math.max(itemsSeen, total);
  }

  /**
   * The wire payload, or null when nothing was measured. Key order and names are the
   * contract consumed by the server's completeness record.
   */
  synchronized Map<String, Object> toPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("version", 1);
    if (scanned) {
      payload.put("filesEncountered", filesEncountered);
      payload.put("bytesEncountered", bytesEncountered);
      payload.put("filesUnreadable", filesUnreadable);
      if (!unreadableReasons.isEmpty()) {
        payload.put("unreadableReasons", new TreeMap<>(unreadableReasons));
      }
    }
    if (itemsSeen >= 0) {
      payload.put("itemsSeen", itemsSeen);
      payload.put("itemsProcessed", itemsProcessed);
    }
    return payload.size() > 1 ? payload : null;
  }
}
