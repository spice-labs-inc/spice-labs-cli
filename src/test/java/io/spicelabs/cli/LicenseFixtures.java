package io.spicelabs.cli;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;

/**
 * Spice Licenses minted against the checked-in test material under
 * {@code src/test/resources/license/}.
 *
 * <p>The material is a P-384 key ({@code signer.key}), its public half as a distribution would
 * ship it ({@code signer.pub}), and an unrelated key ({@code other.key}). None of it is trusted
 * by anything but a test.
 */
final class LicenseFixtures {

  /** The key resource, as an edition manifest would name it. */
  static final String KEY = "license/signer.pub";

  /** A moment inside the licenses minted here. */
  static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

  private LicenseFixtures() {}

  static ECPrivateKey signerKey() {
    return privateKey("license/signer.key");
  }

  static ECPrivateKey otherKey() {
    return privateKey("license/other.key");
  }

  static ECPublicKey signerPublicKey() {
    try (InputStream in = LicenseFixtures.class.getResourceAsStream("/" + KEY)) {
      return License.parseKey(new String(in.readAllBytes()));
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A builder for a Spice License with sensible claims; tests override what they test. */
  static JWTCreator.Builder spiceLicense(String... features) {
    return JWT.create()
        .withHeader(Map.of("typ", License.TYPE))
        .withIssuer("https://spicelabs.io")
        .withJWTId("test-jti")
        .withIssuedAt(Date.from(NOW.minusSeconds(60)))
        .withNotBefore(Date.from(NOW.minusSeconds(60)))
        .withExpiresAt(Date.from(NOW.plusSeconds(365L * 86400)))
        .withClaim("x-version", 2)
        .withClaim("x-type", License.LICENSE_TYPE)
        .withClaim("x-uuid-org", "00000000-0000-0000-0000-000000000001")
        .withClaim(License.FEATURES_CLAIM, List.of(features));
  }

  /** A license signed by the fixture signer: what a licensed customer holds. */
  static String signed(JWTCreator.Builder builder) {
    return builder.sign(Algorithm.ECDSA384(signerPublicKey(), signerKey()));
  }

  /** A license signed by a key that is not the one the build ships. */
  static String signedByStranger(JWTCreator.Builder builder) {
    return builder.sign(Algorithm.ECDSA384(null, otherKey()));
  }

  /** A Spice Pass, as the platform mints it: RS256 header, `x-type` long-duration. */
  static String spicePass() {
    return "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9."
        + base64url("{\"x-type\":\"long-duration\",\"x-uuid-project\":\"p\",\"exp\":4102444800}")
        + ".c2lnbmF0dXJl";
  }

  static String base64url(String json) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
  }

  private static ECPrivateKey privateKey(String resource) {
    try (InputStream in = LicenseFixtures.class.getResourceAsStream("/" + resource)) {
      String pem = new String(in.readAllBytes())
          .replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "")
          .replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(pem);
      return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
