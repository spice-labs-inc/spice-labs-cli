package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The paths a config file names, for the wrapper to mount.
 *
 * <p>The wrapper cannot read TOML, so it takes this list on trust and mounts what it says.
 * What matters is that the section is well-formed even when the file is not — a stack trace
 * here would corrupt the manifest the wrapper parses and break every run.
 */
class ConfigurationPathsTest {

  private static Path write(Path dir, String toml) throws Exception {
    return Files.writeString(dir.resolve("config.toml"), toml);
  }

  @Test
  void nothingIsListedWhenNoPluginDeclaresAKey(@TempDir Path dir) throws Exception {
    // No plugin is mounted in this test, and `spice`'s own settings name no paths.
    String rendered = ConfigurationPaths.render(write(dir, "[analysis]\nthreads = 4\n"));

    assertTrue(rendered.contains(ConfigurationPaths.HEADER), rendered);
    assertFalse(rendered.contains("\nP "), "no keys declared, so no paths: " + rendered);
  }

  @Test
  void aFileThatDoesNotParseStillProducesAWellFormedSection(@TempDir Path dir) throws Exception {
    // The wrapper parses this output. A malformed config file must cost the mounts, not
    // the manifest — the command itself will report the parse error in its own words.
    String rendered = ConfigurationPaths.render(write(dir, "threads = \n"));

    assertTrue(rendered.contains(ConfigurationPaths.HEADER), rendered);
    assertFalse(rendered.contains("Exception"), rendered);
  }

  @Test
  void aMissingFileIsNotFatal(@TempDir Path dir) {
    String rendered = ConfigurationPaths.render(dir.resolve("absent.toml"));

    assertTrue(rendered.contains(ConfigurationPaths.HEADER), rendered);
  }
}
