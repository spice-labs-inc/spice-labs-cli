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

import java.util.Optional;

import io.spicelabs.cli.spi.SpiceContext;

/**
 * The CLI's implementation of {@link SpiceContext} handed to plugins. Keeps plugin
 * behaviour (version reporting, {@code SPICE_PASS} resolution, logging) consistent with
 * the built-in commands. This is the app's concrete impl, not part of the public SPI.
 */
final class DefaultSpiceContext implements SpiceContext {

  private final String version;

  private DefaultSpiceContext(String version) {
    this.version = version;
  }

  static DefaultSpiceContext create() {
    return new DefaultSpiceContext(SpiceLabsCLI.VersionProvider.getVersionString());
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public Optional<String> spicePass() {
    String pass = System.getenv("SPICE_PASS");
    return (pass == null || pass.isBlank()) ? Optional.empty() : Optional.of(pass);
  }
}
