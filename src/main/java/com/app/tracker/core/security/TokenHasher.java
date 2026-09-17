package com.app.tracker.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.4.2 — refresh/verification token'lar duz metin olarak
 * ASLA saklanmaz; sadece SHA-256 hash'i DB'ye yazilir.
 */
public final class TokenHasher {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private TokenHasher() {}

  public static String sha256Hex(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 256-bit rastgele, URL-safe token (Bolum 1.4.2). */
  public static String generateRawToken() {
    byte[] randomBytes = new byte[32];
    SECURE_RANDOM.nextBytes(randomBytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
  }
}
