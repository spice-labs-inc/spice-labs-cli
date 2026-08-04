// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CiTagsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Map<String, Object> parse(String json) throws Exception {
    return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
  }

  @Test
  void detect_githubActions() {
    Map<String, Object> ci = CiTags.detect(Map.of(
        "GITHUB_RUN_ID", "1234567890",
        "GITHUB_SERVER_URL", "https://github.com",
        "GITHUB_REPOSITORY", "spice-labs-inc/spice-labs-cli"));

    assertNotNull(ci);
    assertEquals("github", ci.get("provider"));
    assertEquals("1234567890", ci.get("runId"));
    assertEquals("https://github.com/spice-labs-inc/spice-labs-cli/actions/runs/1234567890",
        ci.get("runUrl"));
  }

  @Test
  void detect_githubWithoutRepoStillReportsRunId() {
    Map<String, Object> ci = CiTags.detect(Map.of("GITHUB_RUN_ID", "42"));

    assertNotNull(ci);
    assertEquals("github", ci.get("provider"));
    assertEquals("42", ci.get("runId"));
    assertFalse(ci.containsKey("runUrl"));
  }

  @Test
  void detect_gitlabPrefersJobUrl() {
    Map<String, Object> ci = CiTags.detect(Map.of(
        "CI_JOB_URL", "https://gitlab.example.com/g/p/-/jobs/77",
        "CI_PIPELINE_URL", "https://gitlab.example.com/g/p/-/pipelines/9"));

    assertNotNull(ci);
    assertEquals("gitlab", ci.get("provider"));
    assertEquals("https://gitlab.example.com/g/p/-/jobs/77", ci.get("runUrl"));
  }

  @Test
  void detect_gitlabFallsBackToPipelineUrl() {
    Map<String, Object> ci = CiTags.detect(Map.of(
        "CI_PIPELINE_URL", "https://gitlab.example.com/g/p/-/pipelines/9"));

    assertNotNull(ci);
    assertEquals("gitlab", ci.get("provider"));
    assertEquals("https://gitlab.example.com/g/p/-/pipelines/9", ci.get("runUrl"));
  }

  @Test
  void detect_jenkins() {
    Map<String, Object> ci = CiTags.detect(Map.of(
        "BUILD_URL", "https://jenkins.example.com/job/app/15/"));

    assertNotNull(ci);
    assertEquals("jenkins", ci.get("provider"));
    assertEquals("https://jenkins.example.com/job/app/15/", ci.get("runUrl"));
  }

  @Test
  void detect_githubWinsOverJenkinsVars() {
    Map<String, Object> ci = CiTags.detect(Map.of(
        "GITHUB_RUN_ID", "42",
        "BUILD_URL", "https://jenkins.example.com/job/app/15/"));

    assertNotNull(ci);
    assertEquals("github", ci.get("provider"));
  }

  @Test
  void detect_returnsNullOutsideCi() {
    assertNull(CiTags.detect(Map.of()));
    assertNull(CiTags.detect(Map.of("PATH", "/usr/bin", "HOME", "/home/u")));
  }

  @Test
  void detect_ignoresBlankValues() {
    assertNull(CiTags.detect(Map.of("GITHUB_RUN_ID", "", "BUILD_URL", " ")));
  }

  @Test
  void merge_addsSpiceCiToExistingTagJson() throws Exception {
    Map<String, Object> ci = CiTags.detect(Map.of("GITHUB_RUN_ID", "42"));
    String merged = CiTags.merge("{\"env\":\"prod\"}", ci);

    Map<String, Object> tags = parse(merged);
    assertEquals("prod", tags.get("env"));
    @SuppressWarnings("unchecked")
    Map<String, Object> spiceCi = (Map<String, Object>) tags.get("spice:ci");
    assertEquals("github", spiceCi.get("provider"));
    assertEquals("42", spiceCi.get("runId"));
  }

  @Test
  void merge_createsTagJsonWhenUserGaveNone() throws Exception {
    Map<String, Object> ci = CiTags.detect(Map.of("BUILD_URL", "https://j/1/"));
    String merged = CiTags.merge(null, ci);

    Map<String, Object> tags = parse(merged);
    assertEquals(1, tags.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> spiceCi = (Map<String, Object>) tags.get("spice:ci");
    assertEquals("jenkins", spiceCi.get("provider"));
  }

  @Test
  void merge_userSpiceCiKeyWins() throws Exception {
    Map<String, Object> ci = CiTags.detect(Map.of("GITHUB_RUN_ID", "42"));
    String user = "{\"spice:ci\":{\"provider\":\"custom\",\"runUrl\":\"https://my.ci/1\"}}";
    String merged = CiTags.merge(user, ci);

    assertEquals(user, merged);
  }

  @Test
  void merge_noCiDetectedLeavesTagJsonUntouched() throws Exception {
    assertNull(CiTags.merge(null, null));
    assertEquals("{\"env\":\"prod\"}", CiTags.merge("{\"env\":\"prod\"}", null));
  }
}
