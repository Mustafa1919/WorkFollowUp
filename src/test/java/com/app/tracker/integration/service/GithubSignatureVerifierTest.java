package com.app.tracker.integration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Imza dogrulamasi webhook ucunun TEK kimlik dogrulamasidir. Docker/Spring context gerektirmez.
 * Bilinen-cevap vektoru GitHub'in resmi dokumantasyonundaki ornektir (secret "It's a Secret to
 * Everybody", govde "Hello, World!").
 */
class GithubSignatureVerifierTest {

  private static final String SECRET = "It's a Secret to Everybody";
  private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
  private static final String GITHUB_DOCS_SIGNATURE =
      "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

  @Test
  void matchesGithubDocumentedTestVector() {
    assertTrue(GithubSignatureVerifier.isValid(SECRET, BODY, GITHUB_DOCS_SIGNATURE));
    assertEquals(GITHUB_DOCS_SIGNATURE, GithubSignatureVerifier.sign(SECRET, BODY));
  }

  @Test
  void tamperedBodyIsRejected() {
    byte[] tampered = "Hello, World?".getBytes(StandardCharsets.UTF_8);

    assertFalse(GithubSignatureVerifier.isValid(SECRET, tampered, GITHUB_DOCS_SIGNATURE));
  }

  @Test
  void wrongSecretIsRejected() {
    assertFalse(GithubSignatureVerifier.isValid("baska-secret", BODY, GITHUB_DOCS_SIGNATURE));
  }

  @Test
  void singleFlippedHexCharacterIsRejected() {
    String flipped =
        GITHUB_DOCS_SIGNATURE.substring(0, GITHUB_DOCS_SIGNATURE.length() - 1)
            + (GITHUB_DOCS_SIGNATURE.endsWith("7") ? "8" : "7");

    assertFalse(GithubSignatureVerifier.isValid(SECRET, BODY, flipped));
  }

  @Test
  void missingOrMalformedHeaderIsRejected() {
    String hex = GITHUB_DOCS_SIGNATURE.substring("sha256=".length());

    assertFalse(GithubSignatureVerifier.isValid(SECRET, BODY, null));
    assertFalse(GithubSignatureVerifier.isValid(SECRET, BODY, ""));
    assertFalse(GithubSignatureVerifier.isValid(SECRET, BODY, "sha256="));
    assertFalse(GithubSignatureVerifier.isValid(SECRET, BODY, hex), "on ek olmadan");
    assertFalse(GithubSignatureVerifier.isValid(SECRET, BODY, "sha1=" + hex), "yanlis algoritma");
    assertFalse(GithubSignatureVerifier.isValid(SECRET, BODY, "SHA256=" + hex), "buyuk harf on ek");
    assertFalse(GithubSignatureVerifier.isValid(SECRET, BODY, "sha256=" + hex.toUpperCase()));
    assertFalse(GithubSignatureVerifier.isValid(SECRET, BODY, GITHUB_DOCS_SIGNATURE + "00"));
  }

  @Test
  void nullInputsNeverValidate() {
    assertFalse(GithubSignatureVerifier.isValid(null, BODY, GITHUB_DOCS_SIGNATURE));
    assertFalse(GithubSignatureVerifier.isValid(SECRET, null, GITHUB_DOCS_SIGNATURE));
  }

  @Test
  void emptyBodyHasItsOwnValidSignature() {
    byte[] empty = new byte[0];

    assertTrue(
        GithubSignatureVerifier.isValid(
            SECRET, empty, GithubSignatureVerifier.sign(SECRET, empty)));
    assertFalse(GithubSignatureVerifier.isValid(SECRET, empty, GITHUB_DOCS_SIGNATURE));
  }
}
