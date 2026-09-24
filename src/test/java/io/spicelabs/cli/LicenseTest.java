package io.spicelabs.cli;

import static io.spicelabs.cli.LicenseFixtures.NOW;
import static io.spicelabs.cli.LicenseFixtures.KEY;
import static io.spicelabs.cli.LicenseFixtures.spiceLicense;
import static io.spicelabs.cli.LicenseFixtures.signed;
import static io.spicelabs.cli.LicenseFixtures.signedByStranger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.auth0.jwt.JWT;

/**
 * The license check, step by step, against the checked-in test key.
 *
 * <p>The editions here are examples: what is tested is that a manifest naming a key makes a run
 * need a pass that verifies against it, and that each way of not having one is reported as
 * itself.
 */
class LicenseTest {

  /** Bulk-and-material, the shape of an airgapped edition, licensed against the test key. */
  static final Edition LICENSED = Edition.of("ex", "Example Pro", true, "bulk", "static-detect", "cbom-material")
      .licensedBy(KEY);
  static final Edition UNLICENSED = Edition.of("ex", "Example Pro", true, "bulk");

  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static License.Outcome check(Edition edition, String license) {
    return License.check(edition, Optional.ofNullable(license), "SPICE_LICENSE", CLOCK);
  }

  private static License.Refused refused(Edition edition, String pass) {
    return assertInstanceOf(License.Refused.class, check(edition, pass));
  }

  // ---- what needs no license -------------------------------------------------------------

  @Test
  void manifestWithoutKey_isUnlicensed() {
    Properties p = new Properties();
    p.setProperty("edition", "plain");
    p.setProperty("features", "bulk");
    Edition edition = Edition.parse(p);
    assertFalse(edition.requiresLicense());
    assertEquals(License.Granted.UNLICENSED, check(edition, null));
    assertEquals("license: not required", License.describe(edition, Optional.empty(), "SPICE_LICENSE"));
  }

  @Test
  void manifestWithKey_requiresLicense() {
    Properties p = new Properties();
    p.setProperty("edition", "pro");
    p.setProperty("features", "bulk");
    p.setProperty("license.key", "spice-license.pub");
    Edition edition = Edition.parse(p);
    assertTrue(edition.requiresLicense());
    assertEquals("spice-license.pub", edition.licenseKey());
    assertTrue(edition.describe().contains("licensed;"), edition.describe());
  }

  @Test
  void unrestrictedBuild_needsNoLicense() {
    assertEquals(License.Granted.UNLICENSED, check(Edition.UNRESTRICTED, null));
  }

  // ---- the key -------------------------------------------------------------------------------

  @Test
  void keyResourceMissing_refuses_notFailsOpen() {
    License.Refused r = refused(LICENSED.licensedBy("license/does-not-exist.pub"), signed(spiceLicense("bulk")));
    assertTrue(r.reason().contains("missing its licensing key"), r.reason());
    assertTrue(r.reason().contains("does-not-exist"), r.reason());
  }

  @Test
  void keyPlaceholderNeverFilledIn_refuses() {
    License.Refused r = refused(LICENSED.licensedBy("${surveyor.license.key}"), signed(spiceLicense("bulk")));
    assertTrue(r.reason().contains("never filled in"), r.reason());
  }

  @Test
  void keyResourceThatIsNotAKey_refuses() {
    // The private key is on the test classpath; a build that shipped it by mistake must not verify with it.
    License.Refused r = refused(LICENSED.licensedBy("license/signer.key"), signed(spiceLicense("bulk")));
    assertTrue(r.reason().contains("missing its licensing key"), r.reason());
    assertTrue(r.reason().contains("no PEM public key"), r.reason());
  }

  // ---- the pass ------------------------------------------------------------------------------

  @Test
  void noPass_refuses_askingForAnAirgappedOne() {
    License.Refused r = refused(LICENSED, null);
    assertTrue(r.reason().contains("requires a Spice License"), r.reason());
    assertEquals(refused(LICENSED, "   ").reason(), r.reason());
  }

  @Test
  void notAToken_refuses() {
    License.Refused r = refused(LICENSED, "hello");
    assertTrue(r.reason().contains("not a Spice License"), r.reason());
  }

  @Test
  void spicePass_refuses_namingTheForm() {
    License.Refused r = refused(LICENSED, LicenseFixtures.spicePass());
    assertTrue(r.reason().contains("a Spice Pass (long-duration)"), r.reason());
    assertTrue(r.reason().contains("needs a Spice License"), r.reason());
  }

  @Test
  void wrongKey_refuses() {
    License.Refused r = refused(LICENSED, signedByStranger(spiceLicense("bulk", "static-detect", "cbom-material")));
    assertTrue(r.reason().contains("not signed by the key this build"), r.reason());
  }

  @Test
  void tamperedPayload_refuses() {
    String pass = signed(spiceLicense("bulk", "static-detect", "cbom-material"));
    String[] parts = pass.split("\\.");
    String widened = parts[0] + "." + LicenseFixtures.base64url(
        "{\"typ\":\"x\",\"x-type\":\"airgapped\",\"exp\":4102444800,\"x-features\":[\"bulk\",\"static-detect\",\"cbom-material\",\"cbom-full\"]}")
        + "." + parts[2];
    License.Refused r = refused(LICENSED, widened);
    assertTrue(r.reason().contains("not signed by the key"), r.reason());
  }

  @Test
  void algorithmNone_refuses() {
    // A token that claims no signature at all, with the right typ so it gets as far as the verifier.
    String header = LicenseFixtures.base64url("{\"alg\":\"none\",\"typ\":\"" + License.TYPE + "\"}");
    String payload = LicenseFixtures.base64url("{\"x-type\":\"airgapped\",\"exp\":4102444800,\"x-features\":[\"bulk\"]}");
    License.Refused r = refused(LICENSED, header + "." + payload + ".");
    assertTrue(r.reason().contains("signed with none"), r.reason());
    assertTrue(r.reason().contains("must be signed with ES384"), r.reason());
  }

  @Test
  void wrongAlgorithm_refuses() {
    String pass = signed(spiceLicense("bulk"));
    // Rewrite alg in the header; the signature no longer matches, but alg is checked first.
    String header = LicenseFixtures.base64url("{\"alg\":\"ES256\",\"typ\":\"" + License.TYPE + "\"}");
    License.Refused r = refused(LICENSED, header + pass.substring(pass.indexOf('.')));
    assertTrue(r.reason().contains("signed with ES256"), r.reason());
  }

  @Test
  void expiredPass_refuses_namingTheDate() {
    License.Refused r = refused(LICENSED, signed(spiceLicense("bulk", "static-detect", "cbom-material")
        .withExpiresAt(Date.from(Instant.parse("2026-01-01T00:00:00Z")))));
    assertTrue(r.reason().contains("expired on 1 January 2026"), r.reason());
  }

  @Test
  void passWithNullExpiry_refuses() {
    // java-jwt serialises withExpiresAt(null) as an explicit "exp": null, and its own presence
    // check lets that through; the verifier must not.
    License.Refused r = refused(LICENSED, signed(spiceLicense("bulk", "static-detect", "cbom-material")
        .withExpiresAt((Date) null)));
    assertTrue(r.reason().contains("carries no expiry"), r.reason());
  }

  @Test
  void passWithoutExpiry_refuses() {
    String pass = signed(JWT.create()
        .withHeader(Map.of("typ", License.TYPE))
        .withClaim("x-type", License.LICENSE_TYPE)
        .withClaim(License.FEATURES_CLAIM, List.of("bulk", "static-detect", "cbom-material")));
    License.Refused r = refused(LICENSED, pass);
    assertTrue(r.reason().contains("carries no expiry"), r.reason());
  }

  @Test
  void passNotYetValid_refuses_andMentionsTheClock() {
    License.Refused r = refused(LICENSED, signed(spiceLicense("bulk", "static-detect", "cbom-material")
        .withNotBefore(Date.from(NOW.plus(Duration.ofDays(2))))));
    assertTrue(r.reason().contains("not valid until"), r.reason());
    assertTrue(r.hints().get(0).contains("clock on this machine reads 1 June 2026"), r.hints().toString());
  }

  @Test
  void clockSkewWithinLeeway_isTolerated() {
    License.Outcome outcome = check(LICENSED, signed(spiceLicense("bulk", "static-detect", "cbom-material")
        .withNotBefore(Date.from(NOW.plus(License.LEEWAY).minusSeconds(30)))));
    assertInstanceOf(License.Granted.class, outcome, outcome.toString());
  }

  @Test
  void wrongTypeClaim_refuses() {
    License.Refused r = refused(LICENSED, signed(spiceLicense("bulk", "static-detect", "cbom-material")
        .withClaim("x-type", "long-duration")));
    assertTrue(r.reason().contains("x-type is wrong"), r.reason());
  }

  // ---- the grant -----------------------------------------------------------------------------

  @Test
  void passWithoutFeatures_refuses() {
    License.Refused r = refused(LICENSED, signed(spiceLicense().withClaim(License.FEATURES_CLAIM, (String) null)));
    assertTrue(r.reason().contains("names no licensed features"), r.reason());
  }

  @Test
  void grantMissingAFeature_refuses_namingIt_andDoesNotNarrow() {
    License.Refused r = refused(LICENSED, signed(spiceLicense("bulk", "static-detect")));
    assertTrue(r.reason().contains("does not cover the Example Pro edition"), r.reason());
    assertTrue(r.reason().contains("lacks cbom-material"), r.reason());
    assertFalse(r.reason().contains("bulk,") || r.reason().contains("lacks bulk"), r.reason());
    assertTrue(r.hints().get(0).contains("covers: bulk, static-detect"), r.hints().toString());
  }

  @Test
  void grantCoveringMore_isGranted() {
    License.Outcome outcome = check(LICENSED, signed(spiceLicense(
        "bulk", "static-detect", "cbom-material", "inventory-surveys", "cbom-full")));
    License.Granted g = assertInstanceOf(License.Granted.class, outcome, outcome.toString());
    assertTrue(g.expiresAt().isPresent());
    assertTrue(g.warning().isEmpty());
  }

  @Test
  void expiryNear_isGranted_withAWarning() {
    License.Outcome outcome = check(LICENSED, signed(spiceLicense("bulk", "static-detect", "cbom-material")
        .withExpiresAt(Date.from(NOW.plus(Duration.ofDays(10))))));
    License.Granted g = assertInstanceOf(License.Granted.class, outcome, outcome.toString());
    assertTrue(g.warning().isPresent());
    assertTrue(g.warning().get().contains("expires on 11 June 2026"), g.warning().get());
  }

  // ---- reporting -------------------------------------------------------------------------------

  @Test
  void describe_saysValidUntil_orWhyNot() {
    Edition edition = LICENSED;
    String pass = signed(spiceLicense("bulk", "static-detect", "cbom-material"));
    // describe() uses the real clock; the fixture pass is minted around NOW and lasts a year from it.
    assertTrue(License.describe(edition, Optional.of(pass), "SPICE_LICENSE").startsWith("license: valid until "),
        License.describe(edition, Optional.of(pass), "SPICE_LICENSE"));
    assertTrue(License.describe(edition, Optional.empty(), "SPICE_LICENSE").startsWith("license: none ("));
  }

  @Test
  void refusalNeverContainsThePass() {
    String pass = signedByStranger(spiceLicense("bulk"));
    License.Refused r = refused(LICENSED, pass);
    assertFalse(r.toString().contains(pass), r.toString());
    assertFalse(r.toString().contains(pass.split("\\.")[1]), r.toString());
  }

  @Test
  void refusalNamesTheVariableTheCredentialCameFrom() {
    // A Spice License may arrive in SPICE_PASS; a refusal must name where the user put it.
    License.Refused r = assertInstanceOf(License.Refused.class,
        License.check(LICENSED, Optional.of(signedByStranger(spiceLicense("bulk"))), "SPICE_PASS", CLOCK));
    assertTrue(r.reason().startsWith("SPICE_PASS was not signed"), r.reason());
  }

  @Test
  void refusalsNeverSendAnyoneToADashboard() {
    // Customers of the licensed editions have no platform account; every hint must say who to ask.
    for (String pass : List.of("hello", LicenseFixtures.spicePass(), signedByStranger(spiceLicense("bulk")))) {
      License.Refused r = refused(LICENSED, pass);
      String all = r.reason() + String.join(" ", r.hints());
      assertFalse(all.contains("Spice Pass page") || all.toLowerCase().contains("dashboard"), all);
    }
    License.Refused none = refused(LICENSED, null);
    assertTrue(String.join(" ", none.hints()).contains("contact Spice Labs"), none.hints().toString());
  }
}
