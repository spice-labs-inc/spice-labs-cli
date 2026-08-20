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

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Prints the path manifest for this image, for the host-side wrapper to consume.
 *
 * <p>The wrapper invokes this once per image (caching the result by image ID) so that its
 * bind-mount decisions reflect the plugins actually present in the image rather than a
 * hardcoded list. Resolving the command tree through {@link CommandSpec#root()} is what
 * makes the manifest complete: by the time this command executes, {@link PluginLoader} has
 * already mounted every plugin onto the root.
 *
 * <p>Given {@code --config}, it also prints the paths that configuration file names, so the
 * wrapper can mount those too. One invocation answers both questions, because the cost here
 * is almost entirely container and JVM startup — measured at ~0.36s, of which the manifest
 * itself is ~30ms — so a second round-trip would double the price of an answer the first
 * one could have carried.
 */
@Command(
    name = "path-manifest",
    hidden = true,
    description = "Print the host wrapper's path manifest for this image.")
public class PathManifestCommand implements Callable<Integer> {

  @Spec
  CommandSpec spec;

  @picocli.CommandLine.Option(
      names = "--config",
      paramLabel = "FILE",
      description = "Also print the paths named by this configuration file.")
  java.nio.file.Path configFile;

  @Override
  public Integer call() {
    // Written straight to stdout, not through the logger: the wrapper parses this and any
    // log decoration would corrupt it.
    StringBuilder out = new StringBuilder(PathManifest.render(spec.root().commandLine()));
    if (configFile != null) {
      out.append(ConfigurationPaths.render(configFile));
    }
    System.out.print(out);
    System.out.flush();
    return 0;
  }
}
