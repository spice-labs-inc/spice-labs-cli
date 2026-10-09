// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** The User-Agent names the edition and the command so the server can count command use. */
class UserAgentTest {

  static final Edition IT = Edition.of("it", "Surveyor IT", false, Edition.INVENTORY_SURVEYS);

  @AfterEach
  void reset() {
    UserAgent.command(List.of());
    Edition.install(null);
    DefaultSpiceContext.install(null);
  }

  @Test
  void productOnly_whenNothingIsKnown() {
    assertEquals("spice-labs-cli/1.8.5", UserAgent.value("1.8.5", Edition.UNRESTRICTED, ""));
  }

  @Test
  void unbrandedBuild_namesTheCommandOnly() {
    assertEquals("spice-labs-cli/1.8.5 (command survey inventory)",
        UserAgent.value("1.8.5", Edition.UNRESTRICTED, "survey inventory"));
  }

  @Test
  void edition_comesBeforeTheCommand() {
    assertEquals("spice-labs-cli/1.8.5 (edition it; command survey runtime)",
        UserAgent.value("1.8.5", IT, "survey runtime"));
  }

  @Test
  void theCommandPicocliChose_isRecorded() {
    Edition.install(IT);
    DefaultSpiceContext context = new DefaultSpiceContext("test", null, IT);
    DefaultSpiceContext.install(context);
    CommandLine cmd = SpiceLabsCLI.newCommandLine(RunConfiguration.EMPTY, IT, context);
    cmd.setOut(new PrintWriter(new StringWriter(), true));
    cmd.setErr(new PrintWriter(new StringWriter(), true));

    cmd.execute("pass", "decode");

    assertEquals("(edition it; command pass decode)",
        UserAgent.value().substring(UserAgent.value().indexOf('(')));
  }
}
