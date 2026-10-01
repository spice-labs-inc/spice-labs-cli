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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Whether a web browser can show the reader an HTML file from here, and how to ask for one.
 *
 * <p>The answer is no when the page would open somewhere the reader is not: inside a
 * container (the {@code spice} wrapper normally runs the CLI in Docker, and then handles
 * {@code spice docs} on the host itself), over SSH (it would open on the remote machine's
 * screen), or on a Linux machine with no display or no application registered for HTML.
 * Otherwise it is the platform's opener: {@code open} on macOS, {@code xdg-open} on Linux and
 * the BSDs, {@code start} on Windows. The wrappers apply the same rules.
 *
 * <p>Whether the opener then works is only known by trying: {@link #open} reports failure when
 * the opener exits with an error, which is what {@code open} does when no application can show
 * the file.
 */
final class HostBrowser {

  private HostBrowser() {}

  /** What {@link #find} found: an opener to use, or the reason there is none. */
  sealed interface Detection permits Found, Unavailable {}

  /** A way to open a file in a browser: the opener's command, before the file's path. */
  record Found(String name, List<String> opener) implements Detection {}

  /** Why no browser can be used from here. */
  record Unavailable(String reason) implements Detection {}

  static Detection find() {
    return find(System.getenv(), System.getProperty("os.name", ""), Files::exists);
  }

  static Detection find(Map<String, String> env, String osName, Predicate<Path> exists) {
    String os = osName.toLowerCase(Locale.ROOT);
    if (exists.test(Path.of("/.dockerenv")) || exists.test(Path.of("/run/.containerenv"))) {
      return new Unavailable("spice is running in a container");
    }
    if (set(env, "SSH_CONNECTION") || set(env, "SSH_TTY")) {
      return new Unavailable("this is an SSH session, so a browser would open on the remote machine");
    }
    if (os.contains("win")) {
      return new Found("start", List.of("cmd", "/c", "start", ""));
    }
    if (os.contains("mac")) {
      Optional<Path> open = onPath(env, "open");
      return open.isPresent() ? new Found("open", List.of(open.get().toString()))
          : new Unavailable("there is no `open` command");
    }
    if (!set(env, "DISPLAY") && !set(env, "WAYLAND_DISPLAY")) {
      return new Unavailable("there is no graphical display (DISPLAY and WAYLAND_DISPLAY are unset)");
    }
    Optional<Path> xdgOpen = onPath(env, "xdg-open");
    if (xdgOpen.isEmpty()) {
      return new Unavailable("there is no `xdg-open` command");
    }
    Optional<Path> xdgMime = onPath(env, "xdg-mime");
    if (xdgMime.isPresent() && output(List.of(xdgMime.get().toString(), "query", "default", "text/html")).isBlank()) {
      return new Unavailable("no application is registered to open HTML");
    }
    return new Found("xdg-open", List.of(xdgOpen.get().toString()));
  }

  /**
   * Ask the opener to show the file; true when it accepted. An opener that is still running
   * after a few seconds has handed the file to a browser that it waits for (some
   * {@code xdg-open} set-ups do), so that also counts as accepted.
   */
  static boolean open(Found browser, Path file) {
    List<String> command = new ArrayList<>(browser.opener());
    command.add(file.toString());
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      if (!p.waitFor(5, TimeUnit.SECONDS)) {
        return true;
      }
      return p.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static boolean set(Map<String, String> env, String name) {
    String value = env.get(name);
    return value != null && !value.isBlank();
  }

  private static Optional<Path> onPath(Map<String, String> env, String command) {
    for (String dir : env.getOrDefault("PATH", "").split(File.pathSeparator)) {
      if (dir.isEmpty()) {
        continue;
      }
      Path candidate = Path.of(dir, command);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private static String output(List<String> command) {
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      byte[] out = p.getInputStream().readAllBytes();
      return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
          ? new String(out, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }
}
