package com.app.tracker.core.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.1 — RS256 imzali, stateless access token. Bilerek hazir
 * bir JWT kutuphanesi (jjwt/nimbus) kullanilmiyor: bu makinede internetten yeni bir Maven
 * bagimliligi cekilip cekilemeyecegi belirsizdi, bu yuzden java.security ile compact JWS formati
 * (header.payload.signature, base64url) dogrudan uygulandi.
 */
@Component
public class JwtService {

  private final JwtKeyProvider keyProvider;
  private final JwtProperties properties;
  private final ObjectMapper objectMapper;

  public JwtService(
      JwtKeyProvider keyProvider, JwtProperties properties, ObjectMapper objectMapper) {
    this.keyProvider = keyProvider;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  /** Payload sadece userId, roller ve jti icerir — e-posta/isim gibi hassas veri KONULMAZ. */
  public String issueAccessToken(UUID userId, List<String> roles, String jti) {
    Instant now = Instant.now();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sub", userId.toString());
    payload.put("roles", roles);
    payload.put("jti", jti);
    payload.put("iat", now.getEpochSecond());
    payload.put("exp", now.plus(properties.getAccessTokenTtl()).getEpochSecond());
    return sign(payload);
  }

  public DecodedJwt verify(String token) {
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new JwtValidationException("Token formati gecersiz.");
    }
    String signingInput = parts[0] + "." + parts[1];
    if (!verifySignature(signingInput, parts[2])) {
      throw new JwtValidationException("Token imzasi gecersiz.");
    }
    Map<String, Object> payload = readPayload(parts[1]);
    long exp = ((Number) payload.get("exp")).longValue();
    if (Instant.now().getEpochSecond() > exp) {
      throw new JwtValidationException("Token suresi dolmus.");
    }
    @SuppressWarnings("unchecked")
    List<String> roles = (List<String>) payload.get("roles");
    return new DecodedJwt(
        UUID.fromString((String) payload.get("sub")), roles, (String) payload.get("jti"));
  }

  private String sign(Map<String, Object> payload) {
    String headerPart = base64UrlEncode(writeJson(Map.of("alg", "RS256", "typ", "JWT")));
    String payloadPart = base64UrlEncode(writeJson(payload));
    String signingInput = headerPart + "." + payloadPart;
    String signaturePart = base64UrlEncode(signBytes(signingInput));
    return signingInput + "." + signaturePart;
  }

  private byte[] signBytes(String signingInput) {
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(keyProvider.getPrivateKey());
      signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
      return signature.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Token imzalanamadi", e);
    }
  }

  private boolean verifySignature(String signingInput, String signaturePartBase64Url) {
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initVerify(keyProvider.getPublicKey());
      signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
      return signature.verify(base64UrlDecode(signaturePartBase64Url));
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  private Map<String, Object> readPayload(String payloadPartBase64Url) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload =
          objectMapper.readValue(base64UrlDecode(payloadPartBase64Url), Map.class);
      return payload;
    } catch (JacksonException e) {
      throw new JwtValidationException("Token govdesi okunamadi.");
    }
  }

  private byte[] writeJson(Object value) {
    return objectMapper.writeValueAsBytes(value);
  }

  private static String base64UrlEncode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] base64UrlDecode(String value) {
    return Base64.getUrlDecoder().decode(value);
  }
}
