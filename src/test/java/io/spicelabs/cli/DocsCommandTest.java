// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025-26 Spice Labs, Inc. & Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DocsCommandTest {

  private static String run(String... args) {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      assertEquals(0, SpiceLabsCLI.newCommandLine().execute(args));
    } finally {
      System.setOut(originalOut);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static JsonNode docs() throws Exception {
    return new ObjectMapper().readTree(run("docs", "--json"));
  }

  @Test
  void printsEveryCommandsHelpByDefault() {
    String manual = run("docs");
    assertTrue(manual.contains("Usage: spice survey inventory"), manual);
    assertTrue(manual.contains("Usage: spice survey runtime"), manual);
    assertFalse(manual.contains("path-manifest"), manual);
  }

  private static JsonNode command(JsonNode docs, String path) {
    for (JsonNode c : docs.get("commands")) {
      if (c.get("command").asText().equals(path)) {
        return c;
      }
    }
    return null;
  }

  @Test
  void printsEveryVisibleCommandWithItsOptionsAsJson() throws Exception {
    JsonNode docs = docs();
    assertEquals("spice", docs.get("cli").asText());
    assertTrue(docs.hasNonNull("version"));

    JsonNode inventory = command(docs, "spice survey inventory");
    assertTrue(inventory != null, "survey inventory is documented");
    assertFalse(inventory.get("description").asText().isBlank());
    List<String> names = new ArrayList<>();
    inventory.get("options").forEach(o -> o.get("names").forEach(n -> names.add(n.asText())));
    assertTrue(names.contains("--output"), names.toString());
    assertTrue(command(docs, "spice survey runtime") != null, "survey runtime is documented");
  }

  @Test
  void documentsItselfAndLeavesOutHiddenCommands() throws Exception {
    JsonNode docs = docs();
    assertEquals(DocsCommand.FORMAT_VERSION, docs.get("format").asInt());
    assertTrue(command(docs, "spice docs") != null);
    assertTrue(command(docs, "spice path-manifest") == null);
  }
}
