// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects subject names that look like they change every run (date, build number, commit
 * hash, UUID, or version tail). Used only to print a soft warning: a volatile name mints a
 * new subject per pipeline run instead of accumulating history under one stable identity.
 * Detection is advisory; the CLI never rejects a subject name.
 */
final class VolatileSubjectDetector {

  /** What made the name look volatile, plus the stable stem left after stripping the tail (may be empty). */
  record Volatility(String kind, String stableStem) {}

  private static final String BOUNDARY = "(?:^|[-_./:@])";

  private record Rule(String kind, Pattern pattern) {}

  private static final List<Rule> RULES = List.of(
      new Rule("a UUID",
          rule("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")),
      new Rule("a date",
          rule("\\d{4}-?(?:0[1-9]|1[0-2])-?(?:0[1-9]|[12]\\d|3[01])")),
      new Rule("a version number",
          rule("v?\\d+\\.\\d+(?:\\.\\d+)?")),
      new Rule("a build or run number",
          rule("\\d{5,}")),
      new Rule("a commit hash",
          rule("(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{7,40}")));

  private static Pattern rule(String tail) {
    return Pattern.compile(BOUNDARY + "(?:" + tail + ")$");
  }

  private VolatileSubjectDetector() {}

  /** Empty when the name looks stable; otherwise the matched kind and the stem before the volatile tail. */
  static Optional<Volatility> check(String subject) {
    if (subject == null || subject.isBlank()) {
      return Optional.empty();
    }
    for (Rule r : RULES) {
      Matcher m = r.pattern().matcher(subject);
      if (m.find()) {
        return Optional.of(new Volatility(r.kind(), subject.substring(0, m.start())));
      }
    }
    return Optional.empty();
  }

  /**
   * Log the soft warning for a volatile-looking subject name. Never throws, never fails the
   * run, never contacts the server.
   */
  static void warnIfVolatile(org.slf4j.Logger log, String subject) {
    check(subject).ifPresent(v -> {
      String suggestion = v.stableStem().isBlank()
          ? "a stable name that identifies what you ship (for example 'payments-api')"
          : "a stable name such as '" + v.stableStem() + "'";
      log.warn("Subject '{}' ends in {}, so every run will create a new subject instead of "
          + "building history under one name. Use {} and put run details in --tag-json; "
          + "the server can alias this name to a stable one later.",
          subject, v.kind(), suggestion);
    });
  }
}
