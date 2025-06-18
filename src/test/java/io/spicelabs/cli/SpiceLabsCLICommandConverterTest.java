package io.spicelabs.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine.TypeConversionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiceLabsCLICommandConverterTest {

  private final SpiceLabsCLI.Command.Converter conv = new SpiceLabsCLI.Command.Converter();

  @Test
  void convert_run() throws Exception {
    assertEquals(SpiceLabsCLI.Command.run, conv.convert("run"));
  }

  @Test
  void convert_scanArtifacts_dashed() throws Exception {
    assertEquals(SpiceLabsCLI.Command.scan_artifacts, conv.convert("scan-artifacts"));
  }

  @Test
  void convert_uploadAdgs() throws Exception {
    assertEquals(SpiceLabsCLI.Command.upload_adgs, conv.convert("upload-adgs"));
  }

  @Test
  void convert_uploadDeploymentEvents() throws Exception {
    assertEquals(SpiceLabsCLI.Command.upload_deployment_events,
        conv.convert("upload-deployment-events"));
  }

  @Test
  void convert_caseInsensitive() throws Exception {
    // because you set setCaseInsensitiveEnumValuesAllowed(true) on the CommandLine
    assertEquals(SpiceLabsCLI.Command.run, conv.convert("RUN"));
    assertEquals(SpiceLabsCLI.Command.scan_artifacts, conv.convert("Scan-Artifacts"));
  }

  @Test
  void convert_invalid_throws() {
    TypeConversionException ex = assertThrows(TypeConversionException.class, () -> conv.convert("foo"));
    assertTrue(ex.getMessage().contains("Invalid command: foo"));
  }
}
