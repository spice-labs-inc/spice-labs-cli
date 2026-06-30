package io.spicelabs.cli;

import java.util.concurrent.Callable;

import io.spicelabs.cli.spi.SpiceCommandPlugin;
import io.spicelabs.cli.spi.SpiceContext;

import picocli.CommandLine.Command;

/**
 * A minimal {@link SpiceCommandPlugin} used to verify ServiceLoader discovery end-to-end.
 * Registered via {@code src/test/resources/META-INF/services/...SpiceCommandPlugin}.
 */
public class EchoTestPlugin implements SpiceCommandPlugin {

  @Override
  public String id() {
    return "echo-test-plugin";
  }

  @Override
  public Object command(SpiceContext context) {
    return new EchoCommand();
  }

  @Command(name = "echo-test", description = "Test plugin subcommand")
  public static class EchoCommand implements Callable<Integer> {
    @Override
    public Integer call() {
      return 0;
    }
  }
}
