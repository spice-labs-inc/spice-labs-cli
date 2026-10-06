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

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Pattern;

import picocli.CommandLine.ITypeConverter;

/**
 * What {@code spice survey inventory} surveys: a file or folder on disk, or a container image
 * in a registry, named with the {@code docker://} prefix (the containers-transports name).
 *
 * <p>The wrappers mount this argument as a host path, and pass a {@code docker://} value
 * through untouched like any other URL (see {@link PathManifest#isPathType}).
 */
final class SurveyInput {

  static final String DOCKER_PREFIX = "docker://";

  /** Other containers-transports names, refused by name rather than read as a path. */
  private static final Set<String> OTHER_TRANSPORTS = Set.of(
      "oci", "oci-archive", "docker-archive", "docker-daemon", "containers-storage", "dir", "sif");

  private static final Pattern URL_SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://");
  private static final Pattern TRANSPORT = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");

  private final String raw;

  SurveyInput(String raw) {
    this.raw = raw;
  }

  String raw() {
    return raw;
  }

  boolean isImage() {
    return raw.startsWith(DOCKER_PREFIX);
  }

  /** The image reference after {@code docker://}. */
  String image() {
    String ref = raw.substring(DOCKER_PREFIX.length());
    if (ref.isBlank()) {
      throw new IllegalArgumentException(
          "docker:// needs an image name, for example docker://nginx:1.27");
    }
    return ref;
  }

  /**
   * The file or folder to survey. A value that names neither an existing path nor a
   * {@code docker://} image is refused with what to type instead.
   */
  Path path() {
    Path path = null;
    try {
      path = Paths.get(raw);
    } catch (InvalidPathException ignored) {
      // Not a path on this system (a Windows JVM given `nginx:1.27`); diagnosed below.
    }
    if (path != null && Files.exists(path)) {
      return path;
    }
    var scheme = URL_SCHEME.matcher(raw);
    if (scheme.find()) {
      throw unsupportedPrefix(scheme.group(1) + "://");
    }
    var transport = TRANSPORT.matcher(raw);
    if (transport.find()) {
      String name = transport.group(1).toLowerCase();
      if (name.equals("docker")) {
        String ref = raw.substring(transport.end()).replaceFirst("^/+", "");
        throw new IllegalArgumentException(
            "No such file or folder: " + raw + ". To survey a container image, use " + DOCKER_PREFIX + ref);
      }
      if (OTHER_TRANSPORTS.contains(name)) {
        throw unsupportedPrefix(transport.group(1) + ":");
      }
    }
    if (looksLikeImage(raw)) {
      throw new IllegalArgumentException(
          "No such file or folder: " + raw + ". To survey a container image, use " + DOCKER_PREFIX + raw);
    }
    throw new IllegalArgumentException("Input path does not exist: " + raw);
  }

  private IllegalArgumentException unsupportedPrefix(String prefix) {
    return new IllegalArgumentException(
        "No such file or folder: " + raw + ". The prefix " + prefix + " is not supported; "
            + "to survey a container image from a registry, use docker://<image>, "
            + "for example docker://nginx:1.27");
  }

  /**
   * Whether a value that is not a path reads as an image reference: it has a tag or digest,
   * or starts with a registry host ({@code ghcr.io/...}, {@code localhost/...}). Anything
   * written as a path ({@code ./x}, {@code /x}, {@code ~/x}, {@code C:\x}, backslashes) is not.
   */
  static boolean looksLikeImage(String value) {
    if (value.isEmpty() || value.contains("\\") || value.contains(" ")
        || value.startsWith("/") || value.startsWith(".") || value.startsWith("~")
        || value.matches("^[A-Za-z]:.*")) {
      return false;
    }
    if (value.contains("@") || value.contains(":")) {
      return true;
    }
    int slash = value.indexOf('/');
    if (slash <= 0) {
      return false;
    }
    String first = value.substring(0, slash);
    return first.contains(".") || "localhost".equalsIgnoreCase(first);
  }

  @Override
  public String toString() {
    return raw;
  }

  static final class Converter implements ITypeConverter<SurveyInput> {
    @Override
    public SurveyInput convert(String value) {
      return new SurveyInput(value);
    }
  }
}
