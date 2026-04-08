// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors

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

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Parent command for all survey types.
 * Usage: spice survey &lt;type&gt; [options]
 */
@Command(
    name = "survey",
    mixinStandardHelpOptions = true,
    description = "Survey software artifacts",
    subcommands = {
        SurveyInventoryCommand.class,
        SurveyRuntimeCommand.class,
    }
)
public class SurveyCommand implements Runnable {

  @Override
  public void run() {
    // No subtype given — print help
    new CommandLine(this).usage(System.out);
  }
}
