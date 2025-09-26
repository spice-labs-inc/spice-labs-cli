package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import picocli.CommandLine.TypeConversionException;

class SpiceLabsCLICommandConverterTest {

  private final SpiceLabsCLI.Command.Converter conv = new SpiceLabsCLI.Command.Converter();

  @Test
  void convert_run() throws Exception {
    assertEquals(SpiceLabsCLI.Command.run, conv.convert("run"));
  }

  @Test
  void convert_surveyArtifacts_dashed() throws Exception {
    assertEquals(SpiceLabsCLI.Command.survey_artifacts, conv.convert("survey-artifacts"));
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
    assertEquals(SpiceLabsCLI.Command.survey_artifacts, conv.convert("Survey-Artifacts"));
  }

  @Test
  void convert_invalid_throws() {
    TypeConversionException ex = assertThrows(TypeConversionException.class, () -> conv.convert("foo"));
    assertTrue(ex.getMessage().contains("Invalid command: foo"));
  }
}
