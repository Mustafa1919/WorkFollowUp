package com.app.tracker.security.support;

import com.app.tracker.core.security.JwtKeyProvider;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Saldiri/bozuk token ureten test yardimcisi. Gecerli uygulama anahtariyla imzalanabilir (test
 * ortaminda dev anahtarlari yuklu): boylece exp/sub gibi alanlari bozuk ama IMZASI GECERLI token
 * uretilir.
 */
public final class JwtForge {

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final JwtKeyProvider keys;

  public JwtForge(JwtKeyProvider keys) {
    this.keys = keys;
  }

  public static String b64(String json) {
    return B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  public static String payloadJson(String subject, long exp) {
    return "{\"sub\":\""
        + subject
        + "\",\"roles\":[\"USER\"],\"jti\":\""
        + java.util.UUID.randomUUID()
        + "\",\"iat\":"
        + (System.currentTimeMillis() / 1000)
        + ",\"exp\":"
        + exp
        + "}";
  }

  /** Uygulamanin ozel anahtariyla RS256 imzali token; payload ham JSON olarak verilir. */
  public String signRs256(String payloadJson) {
    String input = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + b64(payloadJson);
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(keys.getPrivateKey());
      signature.update(input.getBytes(StandardCharsets.UTF_8));
      return input + "." + B64.encodeToString(signature.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code alg:none}, imza bolumu bos. */
  public static String unsigned(String payloadJson) {
    return b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + b64(payloadJson) + ".";
  }

  /**
   * HS256; HMAC sirri olarak acik anahtarin DER baytlari (klasik algoritma karisikligi saldirisi).
   */
  public String hs256WithPublicKeyAsSecret(String payloadJson) {
    String input = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." + b64(payloadJson);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(keys.getPublicKey().getEncoded(), "HmacSHA256"));
      return input + "." + B64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Token'in payload bolumunu (JSON metin olarak) cozer. */
  public static String decodePayload(String jwt) {
    return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
  }
}
