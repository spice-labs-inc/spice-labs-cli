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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The user guide a distribution may carry in the jar, which {@code spice docs} shows. The CLI
 * ships none; a distribution (Surveyor) adds it under {@code spice-guide/}:
 *
 * <ul>
 *   <li>{@code pages.tsv}: the pages in order, one per line as id, tab, title;
 *   <li>{@code guide.md}: the whole guide as Markdown;
 *   <li>{@code pages/<id>.md}: each page on its own;
 *   <li>{@code guide.html}: the whole guide as one self-contained page, routed by URL hash.
 * </ul>
 *
 * <p>Page ids are kebab-case; the distribution's build enforces it.
 */
final class Guide {

  static final String DEFAULT_ROOT = "/spice-guide/";

  /** Where the guide is read from: {@link #DEFAULT_ROOT}, or a test's own resources. */
  private static volatile String root = DEFAULT_ROOT;

  private Guide() {}

  /** A page of the guide: the id {@code spice docs} takes, and its title. */
  record Page(String id, String title) {}

  /** For tests: read the guide from another resource directory ({@code null}: the default). */
  static void useRoot(String resourceRoot) {
    root = resourceRoot == null ? DEFAULT_ROOT : resourceRoot;
  }

  /** The pages in guide order; empty when this build carries no guide. */
  static List<Page> pages() {
    List<Page> pages = new ArrayList<>();
    try (InputStream in = Guide.class.getResourceAsStream(root + "pages.tsv")) {
      if (in == null) {
        return pages;
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        int tab = line.indexOf('\t');
        if (tab > 0) {
          pages.add(new Page(line.substring(0, tab), line.substring(tab + 1)));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return pages;
  }

  /** The Markdown for one page, or for the whole guide when {@code id} is null. */
  static Optional<byte[]> markdown(String id) throws IOException {
    return resource(id == null ? "guide.md" : "pages/" + id + ".md");
  }

  /** The HTML guide, opening at page {@code id} when one is given. */
  static Optional<byte[]> html(String id) throws IOException {
    Optional<byte[]> page = resource("guide.html");
    if (page.isEmpty() || id == null) {
      return page;
    }
    // Ids are kebab-case, so this is safe to embed. The page routes on its URL hash; set one
    // before it reads it, unless the reader already chose a page.
    String html = new String(page.get(), StandardCharsets.UTF_8).replaceFirst(
        "</head>", "<script>if(!location.hash)location.replace('#" + id + "')</script></head>");
    return Optional.of(html.getBytes(StandardCharsets.UTF_8));
  }

  private static Optional<byte[]> resource(String name) throws IOException {
    try (InputStream in = Guide.class.getResourceAsStream(root + name)) {
      return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
    }
  }
}
