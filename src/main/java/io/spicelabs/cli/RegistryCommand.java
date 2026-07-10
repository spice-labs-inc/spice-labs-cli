// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * {@code spice registry} — bulk survey of an entire artifact repository.
 *
 * <p>This command is present <strong>only</strong> when the allspice fat JAR is
 * available at runtime (enterprise image). It delegates to allspice's
 * {@code AllspiceBuilder} in-process via a custom classloader, driving the
 * discover → select → run → upload pipeline.
 *
 * <p>Subcommands mirror allspice's own CLI:
 * <pre>
 *   spice registry discover --config nexus.toml --output discovery.toml
 *   spice registry run      --config nexus.toml --discovery discovery.toml
 *   spice registry status   --config nexus.toml
 *   spice registry list     --config nexus.toml
 *   spice registry retry    --config nexus.toml
 * </pre>
 *
 * <p>State lives in {@code allspice-state/} beside the config file (JGit-backed
 * git repo). Staging/ADG dirs come from the config TOML. When run in Docker, the
 * wrapper script mounts these paths.
 */
@Command(
    name = "registry",
    description = "Bulk survey of an entire artifact repository (enterprise)",
    mixinStandardHelpOptions = true,
    subcommands = {
        RegistryCommand.DiscoverCommand.class,
        RegistryCommand.RunCommand.class,
        RegistryCommand.StatusCommand.class,
        RegistryCommand.ListCommand.class,
        RegistryCommand.RetryCommand.class,
    },
    footer = {
        "",
        "This command requires the enterprise image (allspice).",
        "It is not available in the OSS build.",
        ""
    }
)
public class RegistryCommand implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(RegistryCommand.class);

  @Override
  public Integer call() {
    new CommandLine(this).usage(System.out);
    return 0;
  }

  // ── Shared base for subcommands ──────────────────────────────────────────

  /**
   * Common options for every registry subcommand that needs a config file.
   */
  static abstract class AbstractRegistrySubcommand implements Callable<Integer> {

    @ParentCommand
    protected RegistryCommand parent;

    @Option(names = "--config", required = true,
            description = "Path to the allspice config TOML")
    Path config;

    @Option(names = "--json", description = "Emit machine-readable JSON output")
    boolean json;

    @Option(names = "--verbose", description = "Verbose logging")
    boolean verbose;

    protected abstract String action();
    protected abstract boolean isVoidReturn();

    @Override
    public Integer call() {
      AllspiceLoader loader = AllspiceLoader.getInstance();
      if (loader == null) {
        log.error("allspice is not available. This command requires the enterprise image.");
        return 1;
      }

      try {
        Object builder = loader.getBuilder();
        // withConfig
        loader.withConfigMethod().invoke(builder, config);
        if (json) loader.withJsonMethod().invoke(builder, true);
        if (verbose) loader.withVerboseMethod().invoke(builder, true);

        // Subcommand-specific options (override hooks)
        configureBuilder(loader, builder);

        // Invoke the action
        var method = actionMethod(loader);
        Object result = method.invoke(builder);
        if (!isVoidReturn() && result != null) {
          System.out.println(result.toString());
        }
        return 0;
      } catch (Exception e) {
        Throwable cause = e;
        if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
          cause = e.getCause();
        }
        if (loader.isAllspiceException(cause)) {
          log.error("❌ {}", cause.getMessage());
        } else {
          log.error("Failed to execute registry {}: {}", action(), cause.getMessage(), cause);
        }
        return 1;
      }
    }

    /** Override to set subcommand-specific builder options (e.g. --discovery). */
    protected void configureBuilder(AllspiceLoader loader, Object builder) throws Exception {}

    /** Override to return the reflective Method for this subcommand's action. */
    protected abstract java.lang.reflect.Method actionMethod(AllspiceLoader loader) throws Exception;
  }

  // ── discover ──────────────────────────────────────────────────────────────

  @Command(
      name = "discover",
      description = "Enumerate packages from a repository and write a discovery file",
      mixinStandardHelpOptions = true
  )
  static class DiscoverCommand extends AbstractRegistrySubcommand {

    @Option(names = "--output", description = "Output discovery file path")
    Path output;

    @Override
    protected String action() { return "discover"; }

    @Override
    protected boolean isVoidReturn() { return false; }

    @Override
    protected void configureBuilder(AllspiceLoader loader, Object builder) throws Exception {
      if (output != null) {
        loader.withOutputMethod().invoke(builder, output);
      }
    }

    @Override
    protected java.lang.reflect.Method actionMethod(AllspiceLoader loader) throws Exception {
      return loader.discoverMethod();
    }
  }

  // ── run ───────────────────────────────────────────────────────────────────

  @Command(
      name = "run",
      description = "Survey selected versions and upload results to the platform",
      mixinStandardHelpOptions = true
  )
  static class RunCommand extends AbstractRegistrySubcommand {

    @Option(names = "--discovery", description = "Discovery file (selected packages)")
    Path discovery;

    @Override
    protected String action() { return "run"; }

    @Override
    protected boolean isVoidReturn() { return true; }

    @Override
    protected void configureBuilder(AllspiceLoader loader, Object builder) throws Exception {
      if (discovery != null) {
        loader.withDiscoveryMethod().invoke(builder, discovery);
      }
    }

    @Override
    protected java.lang.reflect.Method actionMethod(AllspiceLoader loader) throws Exception {
      return loader.runMethod();
    }
  }

  // ── status ────────────────────────────────────────────────────────────────

  @Command(
      name = "status",
      description = "Summarize run state from the git-backed state repository",
      mixinStandardHelpOptions = true
  )
  static class StatusCommand extends AbstractRegistrySubcommand {

    @Override
    protected String action() { return "status"; }

    @Override
    protected boolean isVoidReturn() { return false; }

    @Override
    protected java.lang.reflect.Method actionMethod(AllspiceLoader loader) throws Exception {
      return loader.statusMethod();
    }
  }

  // ── list ──────────────────────────────────────────────────────────────────

  @Command(
      name = "list",
      description = "List tracked package versions and their states",
      mixinStandardHelpOptions = true
  )
  static class ListCommand extends AbstractRegistrySubcommand {

    @Override
    protected String action() { return "list"; }

    @Override
    protected boolean isVoidReturn() { return false; }

    @Override
    protected java.lang.reflect.Method actionMethod(AllspiceLoader loader) throws Exception {
      return loader.listMethod();
    }
  }

  // ── retry ─────────────────────────────────────────────────────────────────

  @Command(
      name = "retry",
      description = "Retry failed operations recorded in the state repository",
      mixinStandardHelpOptions = true
  )
  static class RetryCommand extends AbstractRegistrySubcommand {

    @Override
    protected String action() { return "retry"; }

    @Override
    protected boolean isVoidReturn() { return false; }

    @Override
    protected java.lang.reflect.Method actionMethod(AllspiceLoader loader) throws Exception {
      return loader.retryMethod();
    }
  }
}
