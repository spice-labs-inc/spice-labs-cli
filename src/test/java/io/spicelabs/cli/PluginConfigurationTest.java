package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.spicelabs.cli.spi.SpiceCommandPlugin;
import io.spicelabs.cli.spi.SpiceContext;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * What a plugin can and cannot see.
 *
 * <p>Sharing a group between commands is only safe if a command's reach is declared, so
 * these are the tests that make claiming mean something: a plugin gets the groups it named,
 * resolved, and gets nothing else — not another command's settings, and not a group it
 * forgot to ask for.
 */
class PluginConfigurationTest {

  /** A plugin that claims one group, and hands back what it was given. */
  static class ClaimingPlugin implements SpiceCommandPlugin {
    private final List<String> groups;
    SpiceContext context;

    ClaimingPlugin(String... groups) {
      this.groups = List.of(groups);
    }

    @Override
    public String id() {
      return "claiming-plugin";
    }

    @Override
    public List<String> configurationGroups() {
      return groups;
    }

    @Override
    public Object command(SpiceContext context) {
      this.context = context;
      return new Noop();
    }

    @Command(name = "claiming")
    static class Noop implements Callable<Integer> {
      @Override
      public Integer call() {
        return 0;
      }
    }
  }

  private static RunConfiguration configuration(Path dir, String toml) throws Exception {
    return RunConfiguration.load(Files.writeString(dir.resolve("config.toml"), toml));
  }

  private static SpiceContext mount(RunConfiguration run, SpiceCommandPlugin plugin) {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    PluginLoader.registerPlugins(cmd, sharedContext(), run, List.of(plugin));
    return ((ClaimingPlugin) plugin).context;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> group(SpiceContext context, String name) {
    return (Map<String, Object>) context.configuration().get(name);
  }

  private static SpiceContext sharedContext() {
    return new SpiceContext() {
      @Override
      public String version() {
        return "test";
      }

      @Override
      public Optional<String> spicePass() {
        return Optional.empty();
      }
    };
  }

  @Test
  void aPluginGetsTheGroupsItClaimed(@TempDir Path dir) throws Exception {
    RunConfiguration run =
        configuration(
            dir,
            """
            [analysis]
            threads = 16

            [upload]
            chunk_size_mb = 128
            """);
    ClaimingPlugin plugin = new ClaimingPlugin("analysis");

    Map<String, Object> configuration = mount(run, plugin).configuration();

    assertEquals(Map.of("threads", 16L), configuration.get("analysis"));
    assertNull(configuration.get("upload"), "a group it did not claim is not delivered");
  }

  @Test
  void aPluginClaimingNothingGetsNothing(@TempDir Path dir) throws Exception {
    RunConfiguration run = configuration(dir, "[analysis]\nthreads = 16\n");
    ClaimingPlugin plugin = new ClaimingPlugin();

    assertEquals(Map.of(), mount(run, plugin).configuration());
  }

  @Test
  void aPluginSeesItsOwnCommandScopedOverride(@TempDir Path dir) throws Exception {
    RunConfiguration run =
        configuration(
            dir,
            """
            [analysis]
            threads = 16

            [claiming.analysis]
            threads = 2
            """);
    ClaimingPlugin plugin = new ClaimingPlugin("analysis");

    assertEquals(2L, group(mount(run, plugin), "analysis").get("threads"));
  }

  @Test
  void aPluginDoesNotSeeAnotherCommandsOverride(@TempDir Path dir) throws Exception {
    RunConfiguration run =
        configuration(
            dir,
            """
            [analysis]
            threads = 16

            [registry.analysis]
            threads = 2
            """);
    ClaimingPlugin plugin = new ClaimingPlugin("analysis");

    assertEquals(16L, group(mount(run, plugin), "analysis").get("threads"));
  }

  @Test
  void aPluginThatCannotNameItsGroupsStillMounts(@TempDir Path dir) throws Exception {
    // Third-party code: a throwing accessor costs that plugin its configuration, never the
    // run. Claiming nothing is the safe failure — no settings beats another command's.
    RunConfiguration run = configuration(dir, "[analysis]\nthreads = 16\n");
    SpiceCommandPlugin plugin =
        new ClaimingPlugin("analysis") {
          @Override
          public List<String> configurationGroups() {
            throw new IllegalStateException("no idea");
          }
        };

    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    PluginLoader.registerPlugins(cmd, sharedContext(), run, List.of(plugin));

    assertTrue(cmd.getSubcommands().containsKey("claiming"), "the plugin still mounts");
    assertEquals(Map.of(), ((ClaimingPlugin) plugin).context.configuration());
  }

  @Test
  void aGroupNoCommandReadsIsReported(@TempDir Path dir) throws Exception {
    RunConfiguration run =
        configuration(
            dir,
            """
            [analysis]
            threads = 16

            [anaylsis]
            threads = 16
            """);

    List<String> unknown =
        io.spicelabs.config.Groups.unclaimed(
            run.root(), List.of("analysis", "upload"), List.of("survey", "registry"));

    assertEquals(List.of("anaylsis"), unknown);
  }
}
