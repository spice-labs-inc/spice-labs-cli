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

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.spicelabs.cli.spi.SpiceConfiguration;

/**
 * The run's configuration, decoded from a Spice Pass.
 *
 * <p>This is the CLI's implementation of {@link SpiceConfiguration} and the <em>only</em> place
 * a pass is turned into configuration. Built-in commands and plugins both read from a value of
 * this type, so they cannot disagree about what is in force.
 *
 * <p>Every accessor is empty when the pass omits the claim or when there is no pass. A
 * malformed pass yields {@link SpiceConfiguration#EMPTY} rather than an exception: configuration
 * is a convenience here, and no command should fail to start because of it. Commands that
 * genuinely require the pass report that themselves.
 *
 * <p>These values were previously republished as system properties (notably
 * {@code -Dspice.cutoff}) so that in-process plugins could reach them. That channel is gone:
 * the value now travels through {@link io.spicelabs.cli.spi.SpiceContext#configuration()}, which
 * makes it typed, discoverable, and impossible to forge from the command line.
 */
final class PassConfiguration implements SpiceConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PassConfiguration.class);

  private final Instant cutoff;
  private final String uploadServer;
  private final String projectId;
  private final String organizationId;
  private final String userId;
  private final Instant passExpiry;

  private PassConfiguration(SpicePassDecoder decoder) {
    this.cutoff = decoder.getCutoff();
    this.uploadServer = decoder.getUploadServer();
    this.projectId = decoder.getProjectId();
    this.organizationId = decoder.getOrganizationId();
    this.userId = decoder.getUserId();
    this.passExpiry = decoder.getExpiresAt();
  }

  /**
   * The configuration carried by the given pass, or {@link SpiceConfiguration#EMPTY} if it is
   * absent or undecodable.
   *
   * <p>Commands that accept a {@code --spice-pass} override must call this with the pass they
   * actually resolved, so the configuration matches the credential in use.
   */
  static SpiceConfiguration of(String spicePass) {
    if (spicePass == null || spicePass.isBlank()) {
      return SpiceConfiguration.EMPTY;
    }
    try {
      return new PassConfiguration(new SpicePassDecoder(spicePass));
    } catch (RuntimeException e) {
      log.debug("Could not read configuration from the Spice Pass: {}", e.getMessage());
      return SpiceConfiguration.EMPTY;
    }
  }

  @Override
  public Optional<Instant> cutoff() {
    return Optional.ofNullable(cutoff);
  }

  @Override
  public Optional<String> uploadServer() {
    return Optional.ofNullable(uploadServer);
  }

  @Override
  public Optional<String> projectId() {
    return Optional.ofNullable(projectId);
  }

  @Override
  public Optional<String> organizationId() {
    return Optional.ofNullable(organizationId);
  }

  @Override
  public Optional<String> userId() {
    return Optional.ofNullable(userId);
  }

  @Override
  public Optional<Instant> passExpiry() {
    return Optional.ofNullable(passExpiry);
  }
}
