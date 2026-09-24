package io.spicelabs.cli;

import static io.spicelabs.cli.LicenceFixtures.NOW;
import static io.spicelabs.cli.LicenceFixtures.KEY;
import static io.spicelabs.cli.LicenceFixtures.airgappedPass;
import static io.spicelabs.cli.LicenceFixtures.signed;
import static io.spicelabs.cli.LicenceFixtures.signedByStranger;
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
 * The licence check, step by step, against the checked-in test key.
 *
 * <p>The editions here are examples: what is tested is that a manifest naming a key makes a run
 * need a pass that verifies against it, and that each way of not having one is reported as
 * itself.
 */
class LicenceTest {

  /** Bulk-and-material, the shape of an airgapped edition, licensed against the test key. */
  static final Edition LICENSED = Edition.of("ex", "Example Pro", true, "bulk", "static-detect", "cbom-material")
      .licensedBy(KEY);
  static final Edition UNLICENSED = Edition.of("ex", "Example Pro", true, "bulk");

  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static Licence.Outcome check(Edition edition, String pass) {
    return Licence.check(edition, Optional.ofNullable(pass), CLOCK);
  }

  private static Licence.Refused refused(Edition edition, String pass) {
    return assertInstanceOf(Licence.Refused.class, check(edition, pass));
  }

  // ---- what needs no licence -------------------------------------------------------------

  @Test
  void manifestWithoutKey_isUnlicensed() {
    Properties p = new Properties();
    p.setProperty("edition", "plain");
    p.setProperty("features", "bulk");
    Edition edition = Edition.parse(p);
    assertFalse(edition.requiresLicence());
    assertEquals(Licence.Granted.UNLICENSED, check(edition, null));
    assertEquals("licence: not required", Licence.describe(edition, Optional.empty()));
  }

  @Test
  void manifestWithKey_requiresLicence() {
    Properties p = new Properties();
    p.setProperty("edition", "pro");
    p.setProperty("features", "bulk");
    p.setProperty("licence.key", "spice-licence.pub");
    Edition edition = Edition.parse(p);
    assertTrue(edition.requiresLicence());
    assertEquals("spice-licence.pub", edition.licenceKey());
    assertTrue(edition.describe().contains("licensed;"), edition.describe());
  }

  @Test
  void unrestrictedBuild_needsNoLicence() {
    assertEquals(Licence.Granted.UNLICENSED, check(Edition.UNRESTRICTED, null));
  }

  // ---- the key -------------------------------------------------------------------------------

  @Test
  void keyResourceMissing_refuses_notFailsOpen() {
    Licence.Refused r = refused(LICENSED.licensedBy("licence/does-not-exist.pub"), signed(airgappedPass("bulk")));
    assertTrue(r.reason().contains("missing its licensing key"), r.reason());
    assertTrue(r.reason().contains("does-not-exist"), r.reason());
  }

  @Test
  void keyPlaceholderNeverFilledIn_refuses() {
    Licence.Refused r = refused(LICENSED.licensedBy("${surveyor.licence.key}"), signed(airgappedPass("bulk")));
    assertTrue(r.reason().contains("never filled in"), r.reason());
  }

  @Test
  void keyResourceThatIsNotAKey_refuses() {
    // The private key is on the test classpath; a build that shipped it by mistake must not verify with it.
    Licence.Refused r = refused(LICENSED.licensedBy("licence/signer.key"), signed(airgappedPass("bulk")));
    assertTrue(r.reason().contains("missing its licensing key"), r.reason());
    assertTrue(r.reason().contains("no PEM public key"), r.reason());
  }

  // ---- the pass ------------------------------------------------------------------------------

  @Test
  void noPass_refuses_askingForAnAirgappedOne() {
    Licence.Refused r = refused(LICENSED, null);
    assertTrue(r.reason().contains("requires an airgapped Spice Pass"), r.reason());
    assertEquals(refused(LICENSED, "   ").reason(), r.reason());
  }

  @Test
  void notAToken_refuses() {
    Licence.Refused r = refused(LICENSED, "hello");
    assertTrue(r.reason().contains("not a Spice Pass"), r.reason());
  }

  @Test
  void onlinePass_refuses_namingTheForm() {
    Licence.Refused r = refused(LICENSED, LicenceFixtures.onlinePass());
    assertTrue(r.reason().contains("online (long-duration) Spice Pass"), r.reason());
    assertTrue(r.reason().contains("needs an airgapped one"), r.reason());
  }

  @Test
  void wrongKey_refuses() {
    Licence.Refused r = refused(LICENSED, signedByStranger(airgappedPass("bulk", "static-detect", "cbom-material")));
    assertTrue(r.reason().contains("not signed by the key this build"), r.reason());
  }

  @Test
  void tamperedPayload_refuses() {
    String pass = signed(airgappedPass("bulk", "static-detect", "cbom-material"));
    String[] parts = pass.split("\\.");
    String widened = parts[0] + "." + LicenceFixtures.base64url(
        "{\"typ\":\"x\",\"x-type\":\"airgapped\",\"exp\":4102444800,\"x-features\":[\"bulk\",\"static-detect\",\"cbom-material\",\"cbom-full\"]}")
        + "." + parts[2];
    Licence.Refused r = refused(LICENSED, widened);
    assertTrue(r.reason().contains("not signed by the key"), r.reason());
  }

  @Test
  void algorithmNone_refuses() {
    // A token that claims no signature at all, with the right typ so it gets as far as the verifier.
    String header = LicenceFixtures.base64url("{\"alg\":\"none\",\"typ\":\"" + Licence.TYPE + "\"}");
    String payload = LicenceFixtures.base64url("{\"x-type\":\"airgapped\",\"exp\":4102444800,\"x-features\":[\"bulk\"]}");
    Licence.Refused r = refused(LICENSED, header + "." + payload + ".");
    assertTrue(r.reason().contains("signed with none"), r.reason());
    assertTrue(r.reason().contains("must be signed with ES384"), r.reason());
  }

  @Test
  void wrongAlgorithm_refuses() {
    String pass = signed(airgappedPass("bulk"));
    // Rewrite alg in the header; the signature no longer matches, but alg is checked first.
    String header = LicenceFixtures.base64url("{\"alg\":\"ES256\",\"typ\":\"" + Licence.TYPE + "\"}");
    Licence.Refused r = refused(LICENSED, header + pass.substring(pass.indexOf('.')));
    assertTrue(r.reason().contains("signed with ES256"), r.reason());
  }

  @Test
  void expiredPass_refuses_namingTheDate() {
    Licence.Refused r = refused(LICENSED, signed(airgappedPass("bulk", "static-detect", "cbom-material")
        .withExpiresAt(Date.from(Instant.parse("2026-01-01T00:00:00Z")))));
    assertTrue(r.reason().contains("expired on 1 January 2026"), r.reason());
  }

  @Test
  void passWithNullExpiry_refuses() {
    // java-jwt serialises withExpiresAt(null) as an explicit "exp": null, and its own presence
    // check lets that through; the verifier must not.
    Licence.Refused r = refused(LICENSED, signed(airgappedPass("bulk", "static-detect", "cbom-material")
        .withExpiresAt((Date) null)));
    assertTrue(r.reason().contains("carries no expiry"), r.reason());
  }

  @Test
  void passWithoutExpiry_refuses() {
    String pass = signed(JWT.create()
        .withHeader(Map.of("typ", Licence.TYPE))
        .withClaim("x-type", Licence.AIRGAPPED_TYPE)
        .withClaim(Licence.FEATURES_CLAIM, List.of("bulk", "static-detect", "cbom-material")));
    Licence.Refused r = refused(LICENSED, pass);
    assertTrue(r.reason().contains("carries no expiry"), r.reason());
  }

  @Test
  void passNotYetValid_refuses_andMentionsTheClock() {
    Licence.Refused r = refused(LICENSED, signed(airgappedPass("bulk", "static-detect", "cbom-material")
        .withNotBefore(Date.from(NOW.plus(Duration.ofDays(2))))));
    assertTrue(r.reason().contains("not valid until"), r.reason());
    assertTrue(r.hints().get(0).contains("clock on this machine reads 1 June 2026"), r.hints().toString());
  }

  @Test
  void clockSkewWithinLeeway_isTolerated() {
    Licence.Outcome outcome = check(LICENSED, signed(airgappedPass("bulk", "static-detect", "cbom-material")
        .withNotBefore(Date.from(NOW.plus(Licence.LEEWAY).minusSeconds(30)))));
    assertInstanceOf(Licence.Granted.class, outcome, outcome.toString());
  }

  @Test
  void wrongTypeClaim_refuses() {
    Licence.Refused r = refused(LICENSED, signed(airgappedPass("bulk", "static-detect", "cbom-material")
        .withClaim("x-type", "long-duration")));
    assertTrue(r.reason().contains("x-type is wrong"), r.reason());
  }

  // ---- the grant -----------------------------------------------------------------------------

  @Test
  void passWithoutFeatures_refuses() {
    Licence.Refused r = refused(LICENSED, signed(airgappedPass().withClaim(Licence.FEATURES_CLAIM, (String) null)));
    assertTrue(r.reason().contains("names no licensed features"), r.reason());
  }

  @Test
  void grantMissingAFeature_refuses_namingIt_andDoesNotNarrow() {
    Licence.Refused r = refused(LICENSED, signed(airgappedPass("bulk", "static-detect")));
    assertTrue(r.reason().contains("does not cover the Example Pro edition"), r.reason());
    assertTrue(r.reason().contains("lacks cbom-material"), r.reason());
    assertFalse(r.reason().contains("bulk,") || r.reason().contains("lacks bulk"), r.reason());
    assertTrue(r.hints().get(0).contains("covers: bulk, static-detect"), r.hints().toString());
  }

  @Test
  void grantCoveringMore_isGranted() {
    Licence.Outcome outcome = check(LICENSED, signed(airgappedPass(
        "bulk", "static-detect", "cbom-material", "inventory-surveys", "cbom-full")));
    Licence.Granted g = assertInstanceOf(Licence.Granted.class, outcome, outcome.toString());
    assertTrue(g.expiresAt().isPresent());
    assertTrue(g.warning().isEmpty());
  }

  @Test
  void expiryNear_isGranted_withAWarning() {
    Licence.Outcome outcome = check(LICENSED, signed(airgappedPass("bulk", "static-detect", "cbom-material")
        .withExpiresAt(Date.from(NOW.plus(Duration.ofDays(10))))));
    Licence.Granted g = assertInstanceOf(Licence.Granted.class, outcome, outcome.toString());
    assertTrue(g.warning().isPresent());
    assertTrue(g.warning().get().contains("expires on 11 June 2026"), g.warning().get());
  }

  // ---- reporting -------------------------------------------------------------------------------

  @Test
  void describe_saysValidUntil_orWhyNot() {
    Edition edition = LICENSED;
    String pass = signed(airgappedPass("bulk", "static-detect", "cbom-material"));
    // describe() uses the real clock; the fixture pass is minted around NOW and lasts a year from it.
    assertTrue(Licence.describe(edition, Optional.of(pass)).startsWith("licence: valid until "),
        Licence.describe(edition, Optional.of(pass)));
    assertTrue(Licence.describe(edition, Optional.empty()).startsWith("licence: none ("));
  }

  @Test
  void refusalNeverContainsThePass() {
    String pass = signedByStranger(airgappedPass("bulk"));
    Licence.Refused r = refused(LICENSED, pass);
    assertFalse(r.toString().contains(pass), r.toString());
    assertFalse(r.toString().contains(pass.split("\\.")[1]), r.toString());
  }
}
