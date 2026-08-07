// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalyzeStatsTest {

  @TempDir
  Path tempDir;

  @Test
  void scanInput_countsFilesAndBytesRecursively() throws Exception {
    Files.writeString(tempDir.resolve("a.txt"), "12345");
    Path sub = Files.createDirectories(tempDir.resolve("sub"));
    Files.writeString(sub.resolve("b.bin"), "1234567890");

    AnalyzeStats stats = AnalyzeStats.scanInput(tempDir);
    Map<String, Object> payload = stats.toPayload();

    assertNotNull(payload);
    assertEquals(1, payload.get("version"));
    assertEquals(2L, payload.get("filesEncountered"));
    assertEquals(15L, payload.get("bytesEncountered"));
    assertEquals(0L, payload.get("filesUnreadable"));
    assertFalse(payload.containsKey("unreadableReasons"), "no reasons when nothing was unreadable");
    assertFalse(payload.containsKey("itemsSeen"), "no analyzer items without progress ticks");
  }

  @Test
  void scanInput_singleFileInput() throws Exception {
    Path file = tempDir.resolve("only.jar");
    Files.writeString(file, "abc");

    Map<String, Object> payload = AnalyzeStats.scanInput(file).toPayload();

    assertNotNull(payload);
    assertEquals(1L, payload.get("filesEncountered"));
    assertEquals(3L, payload.get("bytesEncountered"));
  }

  @Test
  void recordProgress_reportsLastSeenItems() {
    AnalyzeStats stats = new AnalyzeStats();
    stats.recordProgress(10, 50);
    stats.recordProgress(50, 80);

    Map<String, Object> payload = stats.toPayload();
    assertNotNull(payload);
    assertEquals(80L, payload.get("itemsSeen"));
    assertEquals(50L, payload.get("itemsProcessed"));
    assertFalse(payload.containsKey("filesEncountered"), "no walk counts without a scan");
  }

  @Test
  void toPayload_isNullWhenNothingMeasured() {
    assertNull(new AnalyzeStats().toPayload());
  }

  @Test
  void scanInput_inaccessibleRootCountsAsWalkError() {
    Map<String, Object> payload = AnalyzeStats.scanInput(tempDir.resolve("does-not-exist")).toPayload();

    assertNotNull(payload);
    assertEquals(0L, payload.get("filesEncountered"));
    assertEquals(1L, payload.get("filesUnreadable"));
    assertEquals(Map.of("walk_error", 1L), payload.get("unreadableReasons"));
  }
}
