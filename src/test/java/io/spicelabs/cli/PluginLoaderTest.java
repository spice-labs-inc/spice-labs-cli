package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

import io.spicelabs.cli.spi.SpiceCommandPlugin;
import io.spicelabs.cli.spi.SpiceContext;

import picocli.CommandLine;
import picocli.CommandLine.Command;

class PluginLoaderTest {

  private static SpiceContext ctx() {
    return DefaultSpiceContext.create();
  }

  // ── End-to-end ServiceLoader discovery (via newCommandLine) ────────────────

  @Test
  void serviceLoader_discoversAndMountsPlugin() {
    CommandLine cmd = SpiceLabsCLI.newCommandLine();
    assertTrue(cmd.getSubcommands().containsKey("echo-test"),
        "ServiceLoader-registered plugin should appear as a subcommand");
    assertEquals(0, cmd.execute("echo-test"), "Plugin subcommand should execute");
  }

  @Test
  void builtInCommands_stillPresentWithPlugins() {
    CommandLine cmd = SpiceLabsCLI.newCommandLine();
    assertTrue(cmd.getSubcommands().containsKey("survey"));
    assertTrue(cmd.getSubcommands().containsKey("pass"));
  }

  // ── Defensive loading (injected plugins) ───────────────────────────────────

  @Test
  void goodPlugin_isRegistered() {
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    PluginLoader.registerPlugins(cmd, ctx(), List.of(new EchoTestPlugin()));
    assertTrue(cmd.getSubcommands().containsKey("echo-test"));
  }

  @Test
  void throwingPlugin_isSkipped_othersStillRegister() {
    SpiceCommandPlugin broken = new SpiceCommandPlugin() {
      @Override public String id() { return "broken"; }
      @Override public Object command(SpiceContext c) { throw new RuntimeException("boom"); }
    };
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    PluginLoader.registerPlugins(cmd, ctx(), List.of(broken, new EchoTestPlugin()));
    assertTrue(cmd.getSubcommands().containsKey("echo-test"),
        "A throwing plugin must not prevent other plugins from registering");
  }

  @Test
  void incompatibleApiVersion_isSkipped() {
    SpiceCommandPlugin fromTheFuture = new SpiceCommandPlugin() {
      @Override public String id() { return "future"; }
      @Override public Object command(SpiceContext c) { return new EchoTestPlugin.EchoCommand(); }
      @Override public int apiVersion() { return SpiceContext.API_VERSION + 1; }
    };
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    PluginLoader.registerPlugins(cmd, ctx(), List.of(fromTheFuture));
    assertFalse(cmd.getSubcommands().containsKey("echo-test"),
        "Plugin built against a different SPI version must be skipped");
  }

  @Test
  void nullCommand_isSkipped() {
    SpiceCommandPlugin returnsNull = new SpiceCommandPlugin() {
      @Override public String id() { return "null-cmd"; }
      @Override public Object command(SpiceContext c) { return null; }
    };
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    // Must not throw.
    PluginLoader.registerPlugins(cmd, ctx(), List.of(returnsNull));
  }

  @Test
  void nameCollision_firstWins_secondSkipped() {
    SpiceCommandPlugin first = new SpiceCommandPlugin() {
      @Override public String id() { return "first"; }
      @Override public Object command(SpiceContext c) { return new Dup("first"); }
    };
    SpiceCommandPlugin second = new SpiceCommandPlugin() {
      @Override public String id() { return "second"; }
      @Override public Object command(SpiceContext c) { return new Dup("second"); }
    };
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    PluginLoader.registerPlugins(cmd, ctx(), List.of(first, second));
    assertTrue(cmd.getSubcommands().containsKey("dup"));
    Object userObject = cmd.getSubcommands().get("dup").getCommand();
    assertEquals("first", ((Dup) userObject).owner, "First registration should win a name clash");
  }

  @Test
  void collisionWithBuiltIn_isSkipped() {
    SpiceCommandPlugin clashesSurvey = new SpiceCommandPlugin() {
      @Override public String id() { return "clash"; }
      @Override public Object command(SpiceContext c) { return new SurveyClash(); }
    };
    CommandLine cmd = new CommandLine(new SpiceLabsCLI());
    CommandLine original = cmd.getSubcommands().get("survey");
    PluginLoader.registerPlugins(cmd, ctx(), List.of(clashesSurvey));
    assertSame(original, cmd.getSubcommands().get("survey"),
        "A plugin must not override a built-in command");
  }

  // ── Fixtures ────────────────────────────────────────────────────────────────

  @Command(name = "dup")
  static class Dup implements Callable<Integer> {
    final String owner;
    Dup(String owner) { this.owner = owner; }
    @Override public Integer call() { return 0; }
  }

  @Command(name = "survey")
  static class SurveyClash implements Callable<Integer> {
    @Override public Integer call() { return 0; }
  }
}
