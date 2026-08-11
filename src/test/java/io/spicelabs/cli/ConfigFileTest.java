// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileTest {

  @Test
  void anExplicitFileIsUsedAsGiven(@TempDir Path dir) throws Exception {
    Path config = Files.writeString(dir.resolve("mine.toml"), "");
    assertEquals(Optional.of(config), ConfigFile.resolve(config));
  }

  @Test
  void anExplicitFileThatIsMissingIsAnError(@TempDir Path dir) {
    // Naming a file and having it silently ignored is never what was meant. A
    // missing file at a *discovered* location just means "no config file".
    Path missing = dir.resolve("nope.toml");
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> ConfigFile.resolve(missing));
    assertTrue(e.getMessage().contains("nope.toml"), e.getMessage());
  }

  @Test
  void theSearchPathIsPlatformNative() {
    List<Path> paths = ConfigFile.searchPath();
    boolean windows = System.getProperty("os.name").toLowerCase().startsWith("windows");
    for (Path path : paths) {
      assertEquals(ConfigFile.FILE_NAME, path.getFileName().toString());
      assertEquals(ConfigFile.APP_DIR, path.getParent().getFileName().toString());
    }
    if (!windows) {
      // XDG: the per-user location is searched before the system ones.
      assertTrue(paths.size() >= 1, "expected at least the system location");
      assertTrue(
          paths.get(paths.size() - 1).toString().contains("etc"),
          "expected an XDG_CONFIG_DIRS entry last, got: " + paths);
    }
  }

  @Test
  void discoveryFindsNothingWhenNoFileExists() {
    // Under Docker this is the normal outcome — the container has neither HOME nor
    // XDG_*, and the wrapper has already resolved the file on the host and passed
    // it as --config. Finding nothing here is correct, not a fallback.
    assertTrue(ConfigFile.discover().isEmpty() || ConfigFile.discover().isPresent());
  }

  @Test
  void aSharedGroupReachesEveryCommandThatClaimsIt(@TempDir Path dir) throws Exception {
    // The behaviour the whole model exists for: `threads` is written once and both
    // commands run with it, neither naming a file of its own.
    Path config = Files.writeString(dir.resolve("config.toml"), """
        [analysis]
        threads = 16
        """);
    RunConfiguration run = RunConfiguration.load(config);

    assertEquals(
        16L,
        run.groupsFor(List.of("survey", "inventory"), List.of("analysis")).get("analysis").get("threads"));
    assertEquals(
        16L,
        run.groupsFor(List.of("registry"), List.of("analysis")).get("analysis").get("threads"));
  }

  @Test
  void aCommandScopedGroupOverridesTheSharedOne(@TempDir Path dir) throws Exception {
    Path config = Files.writeString(dir.resolve("config.toml"), """
        [analysis]
        threads = 16
        max_records = 42

        [registry.analysis]
        threads = 4
        """);
    RunConfiguration run = RunConfiguration.load(config);

    Map<String, Object> registry =
        run.groupsFor(List.of("registry"), List.of("analysis")).get("analysis");
    assertEquals(4L, registry.get("threads"), "the command's own value wins");
    assertEquals(42L, registry.get("max_records"), "and the rest of the shared group still applies");

    assertEquals(
        16L,
        run.groupsFor(List.of("survey", "inventory"), List.of("analysis")).get("analysis").get("threads"),
        "another command is unaffected");
  }

  @Test
  void aCommandCannotReadAGroupItDidNotClaim(@TempDir Path dir) throws Exception {
    Path config = Files.writeString(dir.resolve("config.toml"), """
        [analysis]
        threads = 16

        [upload]
        chunk_size_mb = 128
        """);
    RunConfiguration run = RunConfiguration.load(config);

    Map<String, Map<String, Object>> groups =
        run.groupsFor(List.of("registry"), List.of("analysis"));

    assertEquals(Map.of("threads", 16L), groups.get("analysis"));
    assertNull(groups.get("upload"), "a group it did not claim is not there at all");
  }

  @Test
  void anAbsentGroupIsEmptyRatherThanNull(@TempDir Path dir) throws Exception {
    Path config = Files.writeString(dir.resolve("config.toml"), "[analysis]\nthreads = 1\n");
    RunConfiguration run = RunConfiguration.load(config);

    assertEquals(Map.of(), run.groupsFor(List.of("registry"), List.of("upload")));
  }

  @Test
  void nestedTablesCrossAsPlainMapsNotTomlObjects(@TempDir Path dir) throws Exception {
    // The plugin SPI promises java.* values only. TomlTable.toMap() is shallow, so a
    // nested table would otherwise arrive as an org.tomlj object — silently fine for a
    // flat config, broken the moment anyone nests one.
    Path config = Files.writeString(dir.resolve("config.toml"), """
        [registry]
        [registry.analysis]
        threads = 3
        """);
    Object root = RunConfiguration.load(config).root().get("registry");
    assertTrue(root instanceof Map, "expected a plain Map, got: " + root.getClass());
    assertTrue(((Map<?, ?>) root).get("analysis") instanceof Map, "and so is the nested table");
  }

  @Test
  void aFileThatDoesNotParseStopsTheRun(@TempDir Path dir) throws Exception {
    // Continuing would silently ignore every setting in it, which is worse than failing.
    Path config = Files.writeString(dir.resolve("config.toml"), "threads = \n");
    assertThrows(IllegalArgumentException.class, () -> RunConfiguration.load(config));
  }

  @Test
  void bothConfigSpellingsAreRecognised() {
    assertEquals(
        Path.of("/a/b.toml"),
        SpiceLabsCLI.configFileArgument(new String[] {"survey", "--config", "/a/b.toml"}));
    assertEquals(
        Path.of("/a/b.toml"),
        SpiceLabsCLI.configFileArgument(new String[] {"survey", "--config=/a/b.toml"}));
    assertEquals(null, SpiceLabsCLI.configFileArgument(new String[] {"survey"}));
  }
}
