package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which files of a survey's input an artifact cutoff puts out of scope: those holding an entry
 * dated after it, read by spice-probe; never one whose dates cannot be read.
 */
class CutoffFilterTest {

  private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant EARLY = Instant.parse("2025-06-01T00:00:00Z");
  private static final Instant LATE = Instant.parse("2026-03-01T00:00:00Z");

  @TempDir Path input;
  @TempDir Path work;

  @Test
  void onlyFilesHoldingALateEntryAreExcluded() throws IOException {
    Path late = jar(input.resolve("late.jar"), LATE);
    jar(input.resolve("early.jar"), EARLY);
    Files.writeString(input.resolve("README.txt"), "not an archive");
    Files.write(input.resolve("plain.gz"), gzip("no time in this header"));
    Files.createDirectories(input.resolve("lib"));
    Path nested = jar(input.resolve("lib/also-late.jar"), LATE);

    List<Path> excluded = CutoffFilter.lateFiles(input, CUTOFF);

    assertEquals(2, excluded.size(), excluded.toString());
    assertTrue(excluded.contains(late));
    assertTrue(excluded.contains(nested), "files in subdirectories are checked too");
  }

  @Test
  void anInputWithNothingLateExcludesNothing() throws IOException {
    jar(input.resolve("early.jar"), EARLY);
    assertEquals(List.of(), CutoffFilter.lateFiles(input, CUTOFF));
  }

  @Test
  void theIgnoreListNamesEachFileAsFoundAndAbsolute() throws IOException {
    Path relative = Path.of("input-relative", "late.jar");
    Path list = CutoffFilter.ignoreList(List.of(relative), work);
    List<String> lines = Files.readAllLines(list);
    assertTrue(lines.contains(relative.toString()), lines.toString());
    assertTrue(lines.contains(relative.toAbsolutePath().normalize().toString()), lines.toString());
    assertTrue(list.startsWith(work), "the list goes in the survey's scratch directory");
  }

  private static Path jar(Path path, Instant entryTime) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
      ZipEntry entry = new ZipEntry("Main.class");
      entry.setLastModifiedTime(FileTime.from(entryTime));
      out.putNextEntry(entry);
      out.write(1);
      out.closeEntry();
    }
    return path;
  }

  private static byte[] gzip(String text) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream out = new GZIPOutputStream(bytes)) {
      out.write(text.getBytes());
    }
    return bytes.toByteArray();
  }
}
