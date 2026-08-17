package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The wrappers carry two generated regions: the shared argument-walking code, and the
 * built-in commands' manifest used as the last-resort fallback. Both are copies, so both
 * can drift — which is the failure mode this whole change exists to remove. These tests
 * fail the build when a copy is stale, so the fix is mechanical:
 * {@code scripts/update-path-manifest.sh}.
 */
class PathManifestGoldenTest {

  private static final Path ROOT = Path.of(System.getProperty("user.dir"));
  private static final String FIX = "\n\nRun scripts/update-path-manifest.sh to regenerate.";

  @Test
  void bashWrapperEmbedsTheCurrentBuiltInManifest() throws Exception {
    String expected = PathManifest.render(PathManifest.builtInCommandLine(), false);
    assertEquals(expected, region(Files.readString(ROOT.resolve("spice")),
        "  cat <<'SPICE_EMBEDDED_MANIFEST'\n", "SPICE_EMBEDDED_MANIFEST\n"),
        "The manifest embedded in `spice` no longer matches the command tree." + FIX);
  }

  @Test
  void powershellWrapperEmbedsTheCurrentBuiltInManifest() throws Exception {
    String expected = PathManifest.render(PathManifest.builtInCommandLine(), false);
    assertEquals(expected, region(Files.readString(ROOT.resolve("spice.ps1")),
        "$MfEmbeddedManifest = @'\n", "'@\n"),
        "The manifest embedded in `spice.ps1` no longer matches the command tree." + FIX);
  }

  @Test
  void bashWrapperEmbedsTheCanonicalSharedLibrary() throws Exception {
    assertEquals(Files.readString(ROOT.resolve("shared/path-manifest.bash")),
        region(Files.readString(ROOT.resolve("spice")),
            "# ── BEGIN SHARED path-manifest.bash ──\n",
            "# ── END SHARED path-manifest.bash ──\n"),
        "`spice` has drifted from shared/path-manifest.bash." + FIX);
  }

  @Test
  void powershellWrapperEmbedsTheCanonicalSharedLibrary() throws Exception {
    assertEquals(Files.readString(ROOT.resolve("shared/path-manifest.ps1")),
        region(Files.readString(ROOT.resolve("spice.ps1")),
            "# ── BEGIN SHARED path-manifest.ps1 ──\n",
            "# ── END SHARED path-manifest.ps1 ──\n"),
        "`spice.ps1` has drifted from shared/path-manifest.ps1." + FIX);
  }

  /** No wrapper may name a specific flag or mountpoint again. */
  @Test
  void wrappersNoLongerHardcodePathFlags() throws Exception {
    for (String wrapper : new String[] { "spice", "spice.ps1" }) {
      String body = withoutGeneratedManifest(Files.readString(ROOT.resolve(wrapper)));
      assertFalse(body.contains("/mnt/input"), wrapper + " still has a fixed mountpoint");
      assertFalse(body.contains("/mnt/output"), wrapper + " still has a fixed mountpoint");
      assertFalse(body.contains("/mnt/config"), wrapper + " still has a fixed mountpoint");
      assertFalse(body.contains("--discovery"), wrapper + " still names a plugin's flag");
      assertFalse(body.contains("--rogues"), wrapper + " still names a plugin's flag");
    }
  }

  private static String withoutGeneratedManifest(String text) {
    int begin = text.indexOf("# ── BEGIN GENERATED PATH MANIFEST ──");
    int end = text.indexOf("# ── END GENERATED PATH MANIFEST ──");
    if (begin < 0 || end < 0) return text;
    return text.substring(0, begin) + text.substring(end);
  }

  private static String region(String text, String begin, String end) {
    int from = text.indexOf(begin);
    assertTrue(from >= 0, "missing region start: " + begin.trim());
    from += begin.length();
    int to = text.indexOf(end, from);
    assertTrue(to >= 0, "missing region end: " + end.trim());
    return text.substring(from, to);
  }
}
