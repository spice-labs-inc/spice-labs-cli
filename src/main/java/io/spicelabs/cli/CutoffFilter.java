package io.spicelabs.cli;

import io.spicelabs.probe.ArchiveDates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The files of an inventory survey's input that an artifact cutoff puts out of scope.
 *
 * <p>A local file has no server to say when it was published, so the dates inside it decide:
 * spice-probe reads them without unpacking anything (a ZIP's central directory, tar headers, and
 * the like), and one entry dated after the cutoff puts the whole file out of scope. A file whose
 * dates cannot be read, or that is not an archive, is surveyed. Archives inside archives are not
 * opened. Allspice applies the same rule to what it downloads, through the same library, so a
 * cutoff means the same thing to both.
 */
final class CutoffFilter {

  private static final Logger log = LoggerFactory.getLogger(CutoffFilter.class);

  private CutoffFilter() {}

  /** Every regular file under {@code root} holding an entry dated after {@code cutoff}. */
  static List<Path> lateFiles(Path root, Instant cutoff) throws IOException {
    List<Path> late = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      Iterator<Path> regular = files.filter(Files::isRegularFile).iterator();
      while (regular.hasNext()) {
        Path file = regular.next();
        Optional<Instant> at = ArchiveDates.entryAfter(file, cutoff);
        if (at.isPresent()) {
          log.info("Excluding {}: it holds an entry dated {}, after the cutoff", root.relativize(file), at.get());
          late.add(file);
        }
      }
    }
    return late;
  }

  /**
   * A file in {@code dir} listing {@code files} for Goat Rodeo to ignore, one path per line.
   * Goat Rodeo matches the path exactly as it walked it, so each is written both as found and
   * in absolute form.
   */
  static Path ignoreList(List<Path> files, Path dir) throws IOException {
    Set<String> lines = new LinkedHashSet<>();
    for (Path file : files) {
      lines.add(file.toString());
      lines.add(file.toAbsolutePath().normalize().toString());
    }
    return Files.write(Files.createTempFile(dir, "cutoff-", ".ignore"), lines);
  }
}
