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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.AlgorithmMismatchException;
import com.auth0.jwt.exceptions.IncorrectClaimException;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.exceptions.MissingClaimException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

/**
 * Whether this run holds a licence for the edition it is running.
 *
 * <p>An edition that runs airgapped cannot be policed by the platform, so the distribution that
 * builds it ships the licensing public key and the customer holds an <em>airgapped</em> Spice
 * Pass: a second form of the pass, signed with the matching private key (which never leaves the
 * platform's Vault), carrying the features the customer bought. This class checks one against
 * the other, offline.
 *
 * <p>The two forms of the pass are deliberately incompatible. The online form is verified only
 * by the platform that minted it. The airgapped form is a plain RFC 7515 JWS with an explicit
 * type ({@value #TYPE}) and algorithm ({@value #ALGORITHM}), so a stock library can verify it
 * against the key and nothing else will accept it by mistake. Presenting one form where the
 * other is expected is the most likely error, and the refusal says so by name.
 *
 * <p><strong>What this is and is not.</strong> The check runs inside a program the customer
 * controls, so it cannot be tamper-proof and does not try to be: a customer who edits the jar is
 * in breach of contract, not defeated by cryptography. The cryptography is here so the honest
 * path is unambiguous — a licence cannot be fabricated by accident, a copied online pass does
 * not silently work, and an expired one says when it expired.
 *
 * <p>Everything the check needs is decided by the distribution through the feature manifest
 * ({@link Edition#RESOURCE}): {@code licence.key} names a PEM public key (SubjectPublicKeyInfo,
 * EC P-384) on the classpath. A manifest without that key needs no licence; a manifest that
 * names one it cannot load refuses to run rather than running unlicensed, because the likeliest
 * cause is a build that shipped the wrong resources, and that must fail loudly.
 *
 * <p>A bare key rather than a certificate, deliberately: with no revocation and no way to update
 * an airgapped installation, a certificate's chain and validity period bought nothing the jar
 * release cycle does not already provide, and cost a CA. A shipped jar trusts its key until it
 * is replaced; a pass lasts at most a year, which bounds what a leaked one is worth.
 *
 * <p>The check is evaluated once per process by the CLI's execution strategy, after picocli has
 * answered any help or version request and only for commands that do work: diagnostics such as
 * {@code pass decode} and the commands the host wrapper and installer call on the user's behalf
 * stay reachable, so a customer with a bad licence can still see what they hold and support can
 * still ask them to run {@code --version}.
 */
final class Licence {

  /** The {@code typ} header the airgapped form carries (RFC 8725 §3.11: explicit typing). */
  static final String TYPE = "spice-airgap+jwt";

  /** The only signature algorithm accepted. Pinned in the verifier, never read from the token. */
  static final String ALGORITHM = "ES384";

  /** The claim naming the features the licence grants. */
  static final String FEATURES_CLAIM = "x-features";

  /** The {@code x-type} value the airgapped form carries; the online form says "long-duration". */
  static final String AIRGAPPED_TYPE = "airgapped";

  /** Tolerance for the clock of an airgapped machine, which nothing ever sets right. */
  static final Duration LEEWAY = Duration.ofMinutes(5);

  /** How long before expiry a run starts warning: enough for an airgapped site to get a new pass. */
  static final Duration EXPIRY_WARNING = Duration.ofDays(30);

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  private Licence() {}

  /** The result of a check: either the run may proceed, or it may not and here is why. */
  sealed interface Outcome permits Granted, Refused {}

  /**
   * The run may proceed. {@code expiresAt} is when the licence ends; {@code warning}, when
   * present, is one line to show the user (expiry is near).
   */
  record Granted(Optional<Instant> expiresAt, Optional<String> warning) implements Outcome {
    /** A run that needs no licence at all. */
    static final Granted UNLICENSED = new Granted(Optional.empty(), Optional.empty());
  }

  /**
   * The run may not proceed. {@code reason} is one line; {@code hints} follow it, indented,
   * telling the user what to do. Neither ever contains the pass.
   */
  record Refused(String reason, List<String> hints) implements Outcome {
    Refused(String reason, String... hints) {
      this(reason, List.of(hints));
    }
  }

  /** Check the run against the edition's licensing key, with the real clock. */
  static Outcome check(Edition edition, Optional<String> spicePass) {
    return check(edition, spicePass, Clock.systemUTC());
  }

  /**
   * Check the run against the edition's licensing key.
   *
   * <p>The order matters: each step assumes the previous ones passed, so the first failure is
   * the one reported, and it is always the most specific thing the user can act on.
   */
  static Outcome check(Edition edition, Optional<String> spicePass, Clock clock) {
    if (!edition.requiresLicence()) {
      return Granted.UNLICENSED;
    }
    String product = edition.displayName();
    Instant now = clock.instant();

    // 1. The public key this build was shipped with.
    ECPublicKey key;
    try {
      key = loadKey(edition.licenceKey());
    } catch (IOException | GeneralSecurityException e) {
      return new Refused(
          "This build of " + product + " is missing its licensing key (" + e.getMessage() + ").",
          "The distribution that built it must ship the key; this is not something you can fix.",
          "Run 'spice --version' and quote its output to support.");
    }

    // 2. A pass at all.
    if (spicePass.isEmpty() || spicePass.get().isBlank()) {
      return new Refused(
          "The " + product + " edition requires an airgapped Spice Pass.",
          "Set SPICE_PASS to the airgapped pass generated from your project's Spice Pass page.");
    }
    String token = spicePass.get().trim();

    // 3. The right form of pass.
    DecodedJWT unverified;
    try {
      unverified = JWT.decode(token);
    } catch (JWTDecodeException e) {
      return new Refused(
          "SPICE_PASS is not a Spice Pass: " + e.getMessage(),
          "Set SPICE_PASS to the airgapped pass generated from your project's Spice Pass page.");
    }
    if (!TYPE.equals(unverified.getType())) {
      String found = unverified.getClaim("x-type").asString();
      String what = found == null ? "a pass of an unknown form" : "an online (" + found + ") Spice Pass";
      return new Refused(
          "SPICE_PASS holds " + what + ", but the " + product + " edition needs an airgapped one.",
          "The two are not interchangeable: an airgapped pass is verified here, offline, against this",
          "build's licensing key. Generate one from your project's Spice Pass page.");
    }

    // 4. The signature, and the time window. java-jwt checks exp/nbf itself; the exceptions
    //    carry the instants, which are what the user needs to see.
    DecodedJWT verified;
    try {
      // The clock overload lives on BaseVerification, not the Verification interface; the cast
      // is how java-jwt documents supplying one, so the test clock reaches exp/nbf too.
      JWTVerifier.BaseVerification verification = (JWTVerifier.BaseVerification) JWT
          .require(Algorithm.ECDSA384(key, null))
          .withClaimPresence("exp")
          .withClaim("x-type", AIRGAPPED_TYPE)
          .acceptLeeway(LEEWAY.toSeconds());
      verified = verification.build(clock).verify(token);
    } catch (AlgorithmMismatchException e) {
      return new Refused(
          "SPICE_PASS is signed with " + unverified.getAlgorithm() + ", but an airgapped pass must be signed with " + ALGORITHM + ".",
          "Generate a fresh airgapped pass from your project's Spice Pass page.");
    } catch (SignatureVerificationException e) {
      return new Refused(
          "SPICE_PASS was not signed by the key this build of " + product + " trusts.",
          "It may have been altered, or issued for a different distribution.",
          "Generate a fresh airgapped pass from your project's Spice Pass page.");
    } catch (TokenExpiredException e) {
      return new Refused(
          "Your airgapped Spice Pass expired on " + DATE.format(e.getExpiredOn()) + ".",
          "Generate a new one from your project's Spice Pass page; if the licence itself has ended,",
          "contact Spice Labs to renew it.");
    } catch (MissingClaimException e) {
      return new Refused(
          "SPICE_PASS carries no expiry, which an airgapped pass must.",
          "Generate a fresh airgapped pass from your project's Spice Pass page.");
    } catch (IncorrectClaimException e) {
      if ("nbf".equals(e.getClaimName())) {
        Instant nbf = unverified.getNotBeforeAsInstant();
        return new Refused(
            "Your airgapped Spice Pass is not valid until " + (nbf == null ? "later" : DATE.format(nbf)) + ".",
            "The clock on this machine reads " + DATE.format(now) + "; check it before anything else.");
      }
      return new Refused(
          "SPICE_PASS is not an airgapped pass (" + e.getClaimName() + " is wrong).",
          "Generate a fresh airgapped pass from your project's Spice Pass page.");
    }

    // withClaimPresence("exp") accepts an explicit JSON null, so ask the decoded value too: an
    // airgapped pass that never expires is not one the platform mints.
    Instant expiresAt = verified.getExpiresAtAsInstant();
    if (expiresAt == null) {
      return new Refused(
          "SPICE_PASS carries no expiry, which an airgapped pass must.",
          "Generate a fresh airgapped pass from your project's Spice Pass page.");
    }

    // 5. The grant covers the build. Not narrowed: a build that ships more than the licence
    //    covers is refused, never quietly run as a lesser edition.
    List<String> granted = verified.getClaim(FEATURES_CLAIM).asList(String.class);
    if (granted == null) {
      return new Refused(
          "Your airgapped Spice Pass names no licensed features.",
          "Generate a fresh airgapped pass from your project's Spice Pass page.");
    }
    Set<String> missing = new LinkedHashSet<>(edition.features());
    granted.forEach(missing::remove);
    if (!missing.isEmpty()) {
      return new Refused(
          "Your licence does not cover the " + product + " edition: it lacks " + String.join(", ", missing) + ".",
          "It covers: " + String.join(", ", granted) + ".",
          "Contact Spice Labs to extend the licence, or run the edition it covers.");
    }

    Optional<String> warning = Optional.empty();
    if (Duration.between(now, expiresAt).compareTo(EXPIRY_WARNING) < 0) {
      warning = Optional.of("⚠️ Your airgapped Spice Pass expires on " + DATE.format(expiresAt)
          + "; generate a new one before then.");
    }
    return new Granted(Optional.of(expiresAt), warning);
  }

  /** One line for {@code --version}: what the licence check would say, without running anything. */
  static String describe(Edition edition, Optional<String> spicePass) {
    if (!edition.requiresLicence()) {
      return "licence: not required";
    }
    return switch (check(edition, spicePass)) {
      case Granted g -> "licence: valid" + g.expiresAt().map(t -> " until " + DATE.format(t)).orElse("");
      case Refused r -> "licence: none (" + r.reason() + ")";
    };
  }

  /** The licensing public key: a PEM SubjectPublicKeyInfo on the classpath, EC P-384. */
  static ECPublicKey loadKey(String resource) throws IOException, GeneralSecurityException {
    if (resource == null || resource.isBlank()) {
      throw new IOException("the manifest names no key");
    }
    if (resource.startsWith("${")) {
      throw new IOException("the manifest's key entry was never filled in: " + resource);
    }
    String name = resource.startsWith("/") ? resource : "/" + resource;
    try (InputStream in = Licence.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IOException("no resource " + name + " on the classpath");
      }
      return parseKey(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
    }
  }

  static ECPublicKey parseKey(String pem) throws GeneralSecurityException {
    if (!pem.contains("-----BEGIN PUBLIC KEY-----")) {
      // Anything else — a private key, a certificate, an empty file — is the wrong resource,
      // and a private key in particular must never be mistaken for the public half.
      throw new GeneralSecurityException("the key resource holds no PEM public key");
    }
    String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
        .replaceAll("\\s", "");
    byte[] der;
    try {
      der = Base64.getDecoder().decode(body);
    } catch (IllegalArgumentException e) {
      throw new GeneralSecurityException("the key resource is not PEM: " + e.getMessage());
    }
    PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    if (!(key instanceof ECPublicKey ec)) {
      throw new GeneralSecurityException("the licensing key is not an EC key");
    }
    return ec;
  }
}
