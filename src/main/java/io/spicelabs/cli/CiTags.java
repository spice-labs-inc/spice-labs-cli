// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Auto-populates the reserved {@code spice:ci} tag-json namespace from well-known CI
 * environment variables (GitHub Actions, GitLab CI, Jenkins), so a survey run in CI links
 * back to the pipeline run that produced it.
 *
 * <p>Merge semantics: the detected object is added under the {@code spice:ci} key of the
 * user's {@code --tag-json} object. If the user already supplies a {@code spice:ci} key,
 * their value wins unchanged. Outside CI (no recognized variables), nothing is added.
 */
final class CiTags {

  static final String KEY = "spice:ci";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CiTags() {}

  /**
   * Inspect the environment for a recognized CI provider. Returns a map with
   * {@code provider} plus {@code runId} / {@code runUrl} when derivable, or {@code null}
   * when no provider is detected.
   */
  static Map<String, Object> detect(Map<String, String> env) {
    String githubRunId = get(env, "GITHUB_RUN_ID");
    if (githubRunId != null) {
      Map<String, Object> ci = new LinkedHashMap<>();
      ci.put("provider", "github");
      ci.put("runId", githubRunId);
      String server = get(env, "GITHUB_SERVER_URL");
      String repo = get(env, "GITHUB_REPOSITORY");
      if (server != null && repo != null) {
        String base = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
        ci.put("runUrl", base + "/" + repo + "/actions/runs/" + githubRunId);
      }
      return ci;
    }

    String gitlabUrl = get(env, "CI_JOB_URL");
    if (gitlabUrl == null) {
      gitlabUrl = get(env, "CI_PIPELINE_URL");
    }
    if (gitlabUrl != null) {
      Map<String, Object> ci = new LinkedHashMap<>();
      ci.put("provider", "gitlab");
      ci.put("runUrl", gitlabUrl);
      return ci;
    }

    String jenkinsUrl = get(env, "BUILD_URL");
    if (jenkinsUrl != null) {
      Map<String, Object> ci = new LinkedHashMap<>();
      ci.put("provider", "jenkins");
      ci.put("runUrl", jenkinsUrl);
      return ci;
    }

    return null;
  }

  /**
   * Merge a detected {@code spice:ci} object into the user's tag-json string. Returns the
   * merged JSON, or the original string untouched when {@code ciTags} is null or the user
   * already set a {@code spice:ci} key.
   *
   * @param userTagJson the raw {@code --tag-json} value; may be null/blank (treated as {})
   */
  static String merge(String userTagJson, Map<String, Object> ciTags) throws JsonProcessingException {
    if (ciTags == null) {
      return userTagJson;
    }
    Map<String, Object> tags;
    if (userTagJson == null || userTagJson.isBlank()) {
      tags = new LinkedHashMap<>();
    } else {
      tags = MAPPER.readValue(userTagJson, new TypeReference<Map<String, Object>>() {});
    }
    if (tags.containsKey(KEY)) {
      return userTagJson;
    }
    tags.put(KEY, ciTags);
    return MAPPER.writeValueAsString(tags);
  }

  private static String get(Map<String, String> env, String name) {
    String value = env.get(name);
    return (value == null || value.isBlank()) ? null : value;
  }
}
