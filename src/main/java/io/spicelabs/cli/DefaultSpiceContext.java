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

import java.util.Map;
import java.util.Optional;

import io.spicelabs.cli.spi.SpiceContext;
import io.spicelabs.cli.spi.SpicePassClaims;

/**
 * The CLI's implementation of {@link SpiceContext} handed to plugins. Keeps plugin
 * behaviour (version reporting, {@code SPICE_PASS} resolution, configuration) consistent with
 * the built-in commands. This is the app's concrete impl, not part of the public SPI.
 *
 * <p>The credential is read from the environment and decoded <em>once</em>, at construction, and
 * the resulting {@link SpicePassClaims} is shared by every plugin and by the built-in commands
 * that consult {@link #current()}. Resolving it once is what makes "the cutoff in force" a
 * single fact about the run rather than something each caller re-derives.
 *
 * <p>It comes from {@value #PASS_VARIABLE} or {@value #LICENSE_VARIABLE}: a Spice Pass is
 * conventionally set in the first and a Spice License in the second, but either may hold either
 * — what the credential <em>is</em> is decided by the credential, not by its variable. Setting
 * both is an error rather than a precedence rule, because a run holding two credentials cannot
 * say which one it acted under.
 */
final class DefaultSpiceContext implements SpiceContext {

  static final String PASS_VARIABLE = "SPICE_PASS";
  static final String LICENSE_VARIABLE = "SPICE_LICENSE";

  private static volatile DefaultSpiceContext current;

  private final String version;
  private final String spicePass;
  private final String variable;
  private final SpicePassClaims passClaims;
  private final Edition edition;

  // Package-private rather than private so a test can build one with a chosen pass; the class
  // itself is package-private, so this widens nothing beyond this package.
  DefaultSpiceContext(String version, String spicePass) {
    this(version, spicePass, Edition.current());
  }

  DefaultSpiceContext(String version, String spicePass, Edition edition) {
    this(version, spicePass, PASS_VARIABLE, edition);
  }

  DefaultSpiceContext(String version, String spicePass, String variable, Edition edition) {
    this.version = version;
    this.spicePass = spicePass;
    this.variable = variable;
    this.passClaims = PassClaims.of(spicePass);
    this.edition = edition;
  }

  static DefaultSpiceContext create() {
    DefaultSpiceContext context = from(System.getenv(), Edition.current());
    current = context;
    return context;
  }

  /**
   * The context for an environment: the credential from {@value #PASS_VARIABLE} or
   * {@value #LICENSE_VARIABLE}, whichever is set (blank counts as unset, as the Windows wrapper
   * passes both through empty). Both set is refused with {@link IllegalArgumentException}, which
   * {@code main} reports as a usage error.
   */
  static DefaultSpiceContext from(Map<String, String> environment, Edition edition) {
    String pass = environment.get(PASS_VARIABLE);
    String license = environment.get(LICENSE_VARIABLE);
    boolean hasPass = pass != null && !pass.isBlank();
    boolean hasLicense = license != null && !license.isBlank();
    if (hasPass && hasLicense) {
      throw new IllegalArgumentException(
          "Both " + PASS_VARIABLE + " and " + LICENSE_VARIABLE + " are set; set only one.");
    }
    return new DefaultSpiceContext(
        SpiceLabsCLI.VersionProvider.getVersionString(),
        hasLicense ? license : pass,
        hasLicense ? LICENSE_VARIABLE : PASS_VARIABLE,
        edition);
  }

  /**
   * The environment variable the credential came from, for messages that must name it. With no
   * credential at all it is {@value #PASS_VARIABLE}; the license check names the right one to set.
   */
  String credentialVariable() {
    return variable;
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

  /** Tests only: make a chosen context the current one ({@code null} rebuilds from the environment). */
  static void install(DefaultSpiceContext context) {
    current = context;
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

  @Override
  public boolean airgapped() {
    return edition.airgapped();
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
