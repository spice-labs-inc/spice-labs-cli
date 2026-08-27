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

/**
 * Normalizes a user-supplied OCI/Docker image reference into the fully-qualified form
 * {@code oras} accepts.
 *
 * <p>oras is permissive about media types but strict about references: it needs an explicit
 * tag or digest and (for the public default registry) an explicit {@code docker.io} host.
 * Writing {@code docker.io/library/nginx:latest} is the convention Docker CLI users expect,
 * so this turns a bare {@code nginx}, {@code nginx:1.25}, or {@code user/app:tag} into it.
 */
final class ImageReference {

  private ImageReference() {}

  /**
   * Expand a reference to the full {@code registry/path:tag} or {@code registry/path@digest}
   * form. A leading host is kept; a bare hostless name becomes {@code docker.io/library/...};
   * a {@code user/app} (one slash, no host) becomes {@code docker.io/user/app}. A missing
   * tag defaults to {@code latest}; a digest is always preserved verbatim.
   *
   * @throws IllegalArgumentException if the reference cannot be parsed
   */
  static String normalize(String ref) {
    if (ref == null || ref.isBlank()) {
      throw new IllegalArgumentException("Image reference must not be blank");
    }
    String value = ref.trim();

    String digest = null;
    int at = value.indexOf('@');
    if (at >= 0) {
      digest = value.substring(at + 1);
      value = value.substring(0, at);
      if (digest.isBlank()) {
        throw new IllegalArgumentException("Image digest must not be blank: " + ref);
      }
    }

    // Split off a trailing :tag only if it is not a registry port (localhost:5000/...).
    String tag = null;
    String name = value;
    int colon = value.lastIndexOf(':');
    int slash = value.lastIndexOf('/');
    if (colon > slash) {
      tag = value.substring(colon + 1);
      name = value.substring(0, colon);
      if (tag.isBlank()) {
        throw new IllegalArgumentException("Image tag must not be blank: " + ref);
      }
    }
    if (name.isBlank()) {
      throw new IllegalArgumentException("Image name must not be blank: " + ref);
    }

    String host;
    String path;
    int firstSlash = name.indexOf('/');
    if (firstSlash < 0) {
      // Bare single-component name -> docker.io/library/<name>
      host = "docker.io";
      path = "library/" + name;
    } else if (looksLikeHost(name.substring(0, firstSlash))) {
      host = name.substring(0, firstSlash);
      path = name.substring(firstSlash + 1);
    } else {
      // e.g. user/app or namespace/name -> docker.io + the given path
      host = "docker.io";
      path = name;
    }
    if (path.isBlank()) {
      throw new IllegalArgumentException("Image repository must not be blank: " + ref);
    }

    StringBuilder out = new StringBuilder(host).append('/').append(path);
    if (digest != null) {
      out.append('@').append(digest);
    } else {
      out.append(':').append(tag == null ? "latest" : tag);
    }
    return out.toString();
  }

  /**
   * A leading path component is a registry host iff it contains a dot, shortens to
   * {@code localhost}, or carries a port (e.g. {@code localhost:5000}).
   */
  private static boolean looksLikeHost(String component) {
    return component.contains(".") || component.contains(":")
        || "localhost".equalsIgnoreCase(component);
  }
}
