package com.app.tracker.integration.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * GitHub'in {@code X-Hub-Signature-256} header'ini dogrular: {@code "sha256=" +
 * hex(HMAC-SHA256(secret, HAM govde baytlari))}. Govde parse edilmeden, GitHub'in imzaladigi bayt
 * dizisi uzerinden hesaplanir (yeniden serilestirme imzayi bozar). Karsilastirma sabit zamanlidir
 * ({@link MessageDigest#isEqual}) — {@code String.equals} ilk farkli karakterde dondugu icin
 * imzanin bayt bayt tahmin edilmesine izin verirdi.
 */
public final class GithubSignatureVerifier {

  private static final String PREFIX = "sha256=";
  private static final String ALGORITHM = "HmacSHA256";

  private GithubSignatureVerifier() {}

  public static boolean isValid(String secret, byte[] body, String signatureHeader) {
    if (secret == null || body == null || signatureHeader == null) {
      return false;
    }
    if (!signatureHeader.startsWith(PREFIX)) {
      return false;
    }
    String provided = signatureHeader.substring(PREFIX.length());
    String expected = hmacHex(secret, body);
    return MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII));
  }

  /** Testler ve (GitHub'i taklit eden) istemciler icin: gecerli bir header degeri uretir. */
  public static String sign(String secret, byte[] body) {
    return PREFIX + hmacHex(secret, body);
  }

  private static String hmacHex(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 hesaplanamadi", e);
    }
  }
}
