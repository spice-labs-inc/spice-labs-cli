package io.spicelabs.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * The run's credential comes from SPICE_PASS or SPICE_LICENSE, whichever is set, and never both:
 * a run holding two credentials could not say which one it acted under.
 */
class CredentialVariablesTest {

  private static DefaultSpiceContext from(Map<String, String> environment) {
    return DefaultSpiceContext.from(environment, Edition.UNRESTRICTED);
  }

  @Test
  void spicePassAlone_isTheCredential() {
    DefaultSpiceContext context = from(Map.of("SPICE_PASS", "a.b.c"));
    assertEquals(Optional.of("a.b.c"), context.spicePass());
    assertEquals("SPICE_PASS", context.credentialVariable());
  }

  @Test
  void spiceLicenseAlone_isTheCredential() {
    DefaultSpiceContext context = from(Map.of("SPICE_LICENSE", "d.e.f"));
    assertEquals(Optional.of("d.e.f"), context.spicePass());
    assertEquals("SPICE_LICENSE", context.credentialVariable());
  }

  @Test
  void neither_isNoCredential() {
    assertEquals(Optional.empty(), from(Map.of()).spicePass());
  }

  @Test
  void blankCountsAsUnset() {
    // The Windows wrapper passes both variables through, empty when the user set neither.
    DefaultSpiceContext context = from(Map.of("SPICE_PASS", "  ", "SPICE_LICENSE", "d.e.f"));
    assertEquals(Optional.of("d.e.f"), context.spicePass());
    assertEquals("SPICE_LICENSE", context.credentialVariable());
  }

  @Test
  void both_isAnError() {
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
        () -> from(Map.of("SPICE_PASS", "a.b.c", "SPICE_LICENSE", "d.e.f")));
    assertTrue(thrown.getMessage().contains("Both SPICE_PASS and SPICE_LICENSE are set"), thrown.getMessage());
  }
}
