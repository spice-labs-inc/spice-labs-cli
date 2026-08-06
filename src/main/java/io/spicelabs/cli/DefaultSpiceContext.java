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

import io.spicelabs.cli.spi.SpicePassClaims;
import io.spicelabs.cli.spi.SpiceContext;

/**
 * The CLI's implementation of {@link SpiceContext} handed to plugins. Keeps plugin
 * behaviour (version reporting, {@code SPICE_PASS} resolution, configuration) consistent with
 * the built-in commands. This is the app's concrete impl, not part of the public SPI.
 *
 * <p>The pass is read from the environment and decoded <em>once</em>, at construction, and the
 * resulting {@link SpicePassClaims} is shared by every plugin and by the built-in commands
 * that consult {@link #current()}. Resolving it once is what makes "the cutoff in force" a
 * single fact about the run rather than something each caller re-derives.
 */
final class DefaultSpiceContext implements SpiceContext {

  private static volatile DefaultSpiceContext current;

  private final String version;
  private final String spicePass;
  private final SpicePassClaims passClaims;

  // Package-private rather than private so a test can build one with a chosen pass; the class
  // itself is package-private, so this widens nothing beyond this package.
  DefaultSpiceContext(String version, String spicePass) {
    this.version = version;
    this.spicePass = spicePass;
    this.passClaims = PassClaims.of(spicePass);
  }

  static DefaultSpiceContext create() {
    DefaultSpiceContext context = new DefaultSpiceContext(
        SpiceLabsCLI.VersionProvider.getVersionString(), System.getenv("SPICE_PASS"));
    current = context;
    return context;
  }

  /**
   * The context for this run, creating it if the CLI has not built one yet (as happens when a
   * command class is exercised directly by a test). Built-in commands use this so they see the
   * same resolved values as plugins.
   */
  static DefaultSpiceContext current() {
    DefaultSpiceContext context = current;
    return context != null ? context : create();
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public Optional<String> spicePass() {
    return (spicePass == null || spicePass.isBlank()) ? Optional.empty() : Optional.of(spicePass);
  }

  @Override
  public SpicePassClaims passClaims() {
    return passClaims;
  }

  /**
   * Deliberately says nothing about the pass.
   *
   * <p>This holds a live bearer credential. The inherited {@code Object.toString} is already safe,
   * but only by accident: turning this into a record, or reaching for Lombok, would generate one
   * that prints every field and put the credential into any log line that formatted the context.
   * Saying so here means such a change fails a test rather than a security review.
   */
  @Override
  public String toString() {
    return "DefaultSpiceContext[version=" + version + ", spicePass=<redacted>]";
  }

}
