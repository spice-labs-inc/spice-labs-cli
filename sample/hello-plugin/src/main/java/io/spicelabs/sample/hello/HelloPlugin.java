// SPDX-License-Identifier: Apache-2.0
package io.spicelabs.sample.hello;

import java.util.concurrent.Callable;

import io.spicelabs.cli.spi.SpiceCommandPlugin;
import io.spicelabs.cli.spi.SpiceContext;

import picocli.CommandLine.Command;

/**
 * A minimal example of a spice subcommand plugin.
 *
 * <p>It contributes a top-level {@code spice hello} command that prints a greeting. This
 * is all a plugin needs: implement {@link SpiceCommandPlugin}, return a picocli command
 * from {@link #command(SpiceContext)}, and register the class in
 * {@code META-INF/services/io.spicelabs.cli.spi.SpiceCommandPlugin}. Build with
 * {@code mvn -f sample/hello-plugin package} (writes the jar to {@code dist/}) and symlink
 * this directory into spice's {@code plugins/} to include it.
 */
public class HelloPlugin implements SpiceCommandPlugin {

  @Override
  public String id() {
    return "hello";
  }

  @Override
  public Object command(SpiceContext context) {
    return new HelloCommand(context);
  }

  @Command(
      name = "hello",
      mixinStandardHelpOptions = true,
      description = "Print a friendly greeting (sample plugin).")
  public static class HelloCommand implements Callable<Integer> {

    private final SpiceContext context;

    HelloCommand(SpiceContext context) {
      this.context = context;
    }

    @Override
    public Integer call() {
      // SpiceContext gives plugins the same shared services the built-in commands use.
      System.out.println("Hello world! (from the spice sample plugin, CLI " + context.version() + ")");
      return 0;
    }
  }
}
