package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The feature manifest a distribution ships, as the CLI reads it. */
class EditionTest {

  @AfterEach
  void reset() {
    Edition.install(null);
  }

  private static Properties props(String... kv) {
    Properties p = new Properties();
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return p;
  }

  @Test
  void parse_absentResource_isUnrestricted() {
    // An ordinary build of this repository ships no manifest.
    assertSame(Edition.UNRESTRICTED, Edition.load());
  }

  @Test
  void parse_absentFeaturesKey_isUnrestricted() {
    Edition e = Edition.parse(props("edition", "cli"));
    assertSame(Edition.UNRESTRICTED, e);
    assertTrue(e.has(Edition.RUNTIME_SURVEYS));
  }

  @Test
  void parse_readsEditionAirgappedAndFeatures() {
    Edition e = Edition.parse(props("edition", "compact", "edition.name", "Example Compact Edition",
        "airgapped", "true", "features", "bulk,static-detect,cbom-material"));
    assertEquals("compact", e.id());
    assertEquals("Example Compact Edition", e.displayName());
    assertTrue(e.airgapped());
    assertTrue(e.has("bulk"));
    assertFalse(e.has(Edition.INVENTORY_SURVEYS));
    assertFalse(e.has(Edition.RUNTIME_SURVEYS));
    assertTrue(e.branded());
  }

  @Test
  void parse_featuresThisCliDoesNotActOn_areCarriedNotRejected() {
    // A distribution describes its whole edition; most of the list names capabilities that
    // plugins provide, and this CLI has no business interpreting them.
    Edition e = Edition.parse(props("edition", "x", "features", "bulk, teleport ,cbom-material"));
    assertEquals(Set.of("bulk", "teleport", "cbom-material"), e.features());
    assertFalse(e.has(Edition.INVENTORY_SURVEYS));
  }

  @Test
  void parse_airgappedDefaultsFalse() {
    Edition e = Edition.parse(props("edition", "plain", "features", "inventory-surveys"));
    assertFalse(e.airgapped());
    assertEquals("plain", e.displayName());
  }

  @Test
  void describe_namesEditionAndFeatures() {
    Edition e = Edition.of("compact", "Example Compact Edition", true, "bulk");
    assertEquals("Example Compact Edition (compact): airgapped; features: bulk", e.describe());
  }

  @Test
  void current_isUnrestricted_inPlainBuild() {
    Edition.install(null);
    assertSame(Edition.UNRESTRICTED, Edition.current());
    assertFalse(Edition.current().branded());
  }
}
