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
 * <p>{@code oci://} is accepted as the same registry pull (the name Helm and Flux use for it).
 * A saved image folder (an OCI layout) needs no prefix: it is surveyed as a folder.
 *
 * <p>The wrappers mount this argument as a host path, and pass a {@code docker://} or
 * {@code oci://} value through untouched like any other URL (see {@link PathManifest#isPathType}).
 */
final class SurveyInput {

  static final String DOCKER_PREFIX = "docker://";
  static final String OCI_PREFIX = "oci://";

  /** Other containers-transports names, refused by name rather than read as a path. */
  private static final Set<String> OTHER_TRANSPORTS = Set.of(
      "oci", "oci-layout", "oci-archive", "docker-archive", "docker-daemon", "containers-storage", "dir", "sif");

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
    return prefix() != null;
  }

  private String prefix() {
    if (raw.startsWith(DOCKER_PREFIX)) {
      return DOCKER_PREFIX;
    }
    return raw.startsWith(OCI_PREFIX) ? OCI_PREFIX : null;
  }

  /** The image reference after {@code docker://} or {@code oci://}. */
  String image() {
    String prefix = prefix();
    String ref = raw.substring(prefix.length());
    if (ref.isBlank()) {
      throw new IllegalArgumentException(
          prefix + " needs an image name, for example " + prefix + "nginx:1.27");
    }
    return ref;
  }

  /**
   * The file or folder to survey. A value that names neither an existing path nor an
   * image ({@code docker://} or {@code oci://}) is refused with what to type instead.
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
      if (name.equals("oci") || name.equals("oci-layout")) {
        throw new IllegalArgumentException(unsupportedPrefix(transport.group(1) + ":").getMessage()
            + ". To survey a saved image folder (an OCI layout), give the folder's path with no prefix");
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
            + "to survey a container image from a registry, use docker://<image> or oci://<image>, "
            + "for example docker://nginx:1.27");
  }

  /**
   * The subject an image survey gets when none is given: the repository name without tag or
   * digest, so every version of an image is one subject. Docker Hub drops its host, and its
   * official images their {@code library/} too: {@code nginx:1.27} gives {@code nginx},
   * {@code user/app:2} gives {@code user/app}, {@code localhost:5000/app:1} keeps its port.
   */
  static String defaultSubject(String image) {
    String ref = ImageReference.normalize(image);
    int at = ref.indexOf('@');
    if (at >= 0) {
      ref = ref.substring(0, at);
    } else {
      int colon = ref.lastIndexOf(':');
      if (colon > ref.lastIndexOf('/')) {
        ref = ref.substring(0, colon);
      }
    }
    for (String hub : new String[] {"docker.io/", "index.docker.io/"}) {
      if (ref.startsWith(hub)) {
        ref = ref.substring(hub.length());
        return ref.startsWith("library/") ? ref.substring("library/".length()) : ref;
      }
    }
    return ref;
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
