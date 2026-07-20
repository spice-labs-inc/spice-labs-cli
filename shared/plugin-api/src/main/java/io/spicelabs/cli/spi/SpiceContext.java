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

package io.spicelabs.cli.spi;

import java.util.Optional;

/**
 * Shared services that {@code spice} passes to a {@link SpiceCommandPlugin} when it
 * builds its command, so plugins can behave consistently with built-in commands (same
 * version reporting, same {@code SPICE_PASS} resolution).
 *
 * <p>The surface is intentionally small and <strong>dependency-free</strong> (only
 * {@code java.*} types) so the {@code spice-plugin-api} artifact imposes nothing on
 * plugin authors and can be compiled in any context. It may grow over time (e.g. an
 * upload helper, output-directory conventions, a global {@code --json} flag); any
 * breaking change is signalled by bumping {@link #API_VERSION}.
 *
 * <p>Plugins that want logging should obtain their own logger (e.g. via SLF4J); the
 * context deliberately does not expose one, to keep this contract dependency-free.
 */
public interface SpiceContext {

  /**
   * The SPI contract version. Bumped when {@link SpiceCommandPlugin} or this interface
   * changes incompatibly; {@code spice} only mounts plugins whose
   * {@link SpiceCommandPlugin#apiVersion()} matches this value.
   */
  int API_VERSION = 1;

  /** The running {@code spice} CLI version (e.g. for plugin {@code --version} output). */
  String version();

  /**
   * The resolved Spice Pass (JWT) from the {@code SPICE_PASS} environment variable, or
   * empty if it is not set. Plugins that upload to the platform should use this rather
   * than reading the environment directly, so resolution stays consistent.
   */
  Optional<String> spicePass();
}
