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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.spicelabs.cli.spi.SpicePassClaims;
import io.spicelabs.cli.spi.SpiceContext;

/**
 * The {@link SpiceContext} handed to one plugin: the shared run context, plus the
 * configuration groups that plugin claimed.
 *
 * <p>A plugin gets exactly the groups it named in
 * {@link io.spicelabs.cli.spi.SpiceCommandPlugin#configurationGroups()}, already resolved:
 * the shared {@code [group]} table, overlaid by the command-scoped {@code [<path>.group]}
 * table, overlaid by the environment. It never sees the file, never learns where its tables
 * sit, and cannot reach a group it did not claim — which is what makes sharing a group
 * between commands safe.
 *
 * <p><strong>Why the command path is bound after construction.</strong> A plugin's
 * command-scoped tables live at its command path, but {@code spice} does not know that path
 * until {@link io.spicelabs.cli.spi.SpiceCommandPlugin#command} has returned and picocli has
 * been asked for the command's name — and {@code command()} needs a context. The ordering
 * works because a plugin reads its configuration when its command *executes*, not when it is
 * built, so {@link PluginLoader} binds the path immediately after mounting and well before
 * anything runs. Until then {@link #configuration()} is empty rather than wrong.
 */
final class PluginContext implements SpiceContext {

  private final SpiceContext shared;
  private final RunConfiguration runConfiguration;
  private final List<String> groups;
  private volatile String[] commandPath;

  PluginContext(SpiceContext shared, RunConfiguration runConfiguration, List<String> groups) {
    this.shared = shared;
    this.runConfiguration = runConfiguration;
    this.groups = List.copyOf(groups);
  }

  /** The groups this plugin claimed, for the whole-file typo check. */
  List<String> claimedGroups() {
    return groups;
  }

  /**
   * Record where this plugin's command was mounted, so its table can be found.
   *
   * @param path the command path, e.g. {@code ["registry"]} or {@code ["survey", "static"]}
   */
  void bindCommandPath(String... path) {
    this.commandPath = path;
  }

  @Override
  public String version() {
    return shared.version();
  }

  @Override
  public Optional<String> spicePass() {
    return shared.spicePass();
  }

  @Override
  public SpicePassClaims passClaims() {
    return shared.passClaims();
  }

  @Override
  public Map<String, Object> configuration() {
    String[] path = commandPath;
    if (path == null || groups.isEmpty()) {
      return Map.of();
    }
    return runConfiguration.claimedGroups(List.of(path), groups);
  }

  /**
   * Deliberately says nothing about the pass or the configuration.
   *
   * <p>A plugin context carries the Spice Pass and the groups a plugin claimed, either of which a
   * caller might reasonably print while working out why a plugin did something. Naming the groups
   * is enough to tell two contexts apart.
   */
  @Override
  public String toString() {
    return "PluginContext[groups=" + groups + ", spicePass=<redacted>, configuration=<redacted>]";
  }
}
