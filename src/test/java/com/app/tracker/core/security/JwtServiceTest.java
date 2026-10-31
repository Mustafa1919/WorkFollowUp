package com.app.tracker.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Docker/Spring context gerektirmeyen tek gercek dogrulama: hand-rolled RS256 imzalamanin (bkz.
 * JwtService) gercekten calistigini kanitlar. Dev PEM anahtarlariyla gercek bir imzala/dogrula turu
 * yapar.
 */
class JwtServiceTest {

  private final JwtProperties properties = newProperties();
  private final JwtKeyProvider keyProvider =
      new JwtKeyProvider(properties, new DefaultResourceLoader(), new StandardEnvironment());
  private final JwtService jwtService =
      new JwtService(keyProvider, properties, JsonMapper.builder().build());

  private static JwtProperties newProperties() {
    JwtProperties properties = new JwtProperties();
    properties.setPrivateKeyLocation("classpath:keys/dev-jwt-private.pem");
    properties.setPublicKeyLocation("classpath:keys/dev-jwt-public.pem");
    return properties;
  }

  private static String b64(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  /** Gecerli imzali, govdesi serbest token uretir. */
  private String signed(String bodyJson) throws Exception {
    String input = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + b64(bodyJson);
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keyProvider.getPrivateKey());
    signature.update(input.getBytes(StandardCharsets.UTF_8));
    return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
  }

  private static String body(String exp, String sub, String roles, String jti) {
    return "{\"exp\":" + exp + ",\"sub\":" + sub + ",\"roles\":" + roles + ",\"jti\":" + jti + "}";
  }

  private static final String OK_SUB = "\"" + UUID.randomUUID() + "\"";

  private static String future() {
    return String.valueOf(Instant.now().getEpochSecond() + 600);
  }

  private void assertRejected(String token) {
    assertThrows(JwtValidationException.class, () -> jwtService.verify(token));
  }

  @Test
  void badBase64SignatureIsRejected() {
    assertRejected("a.b.%%%");
  }

  @Test
  void badBase64BodyIsRejected() throws Exception {
    String good = signed(body(future(), OK_SUB, "[]", "\"j\""));
    String[] p = good.split("\\.");
    // govde bozulunca imza da gecersiz olur; yine de yalniz JwtValidationException beklenir
    assertRejected(p[0] + ".%%%." + p[2]);
    // gecerli imzali ama base64url olmayan govde
    String input = p[0] + ".%%%";
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keyProvider.getPrivateKey());
    signature.update(input.getBytes(StandardCharsets.UTF_8));
    assertRejected(
        input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
  }

  @Test
  void invalidClaimsAreRejected() throws Exception {
    String f = future();
    assertRejected(signed("{\"sub\":" + OK_SUB + ",\"roles\":[],\"jti\":\"j\"}"));
    assertRejected(signed(body("\"abc\"", OK_SUB, "[]", "\"j\"")));
    assertRejected(signed("{\"exp\":" + f + ",\"roles\":[],\"jti\":\"j\"}"));
    assertRejected(signed(body(f, "5", "[]", "\"j\"")));
    assertRejected(signed(body(f, "\"nope\"", "[]", "\"j\"")));
    assertRejected(signed("{\"exp\":" + f + ",\"sub\":" + OK_SUB + ",\"jti\":\"j\"}"));
    assertRejected(signed(body(f, OK_SUB, "\"USER\"", "\"j\"")));
    assertRejected(signed(body(f, OK_SUB, "[\"A\",1]", "\"j\"")));
    assertRejected(signed("{\"exp\":" + f + ",\"sub\":" + OK_SUB + ",\"roles\":[]}"));
    assertRejected(signed(body(f, OK_SUB, "[]", "\"\"")));
    assertRejected(signed("[]"));
    assertRejected(signed("\"x\""));
    assertRejected(signed("null"));
  }

  @Test
  void outOfRangeExpIsRejected() throws Exception {
    assertRejected(signed(body(String.valueOf(Long.MAX_VALUE), OK_SUB, "[]", "\"j\"")));
  }

  @Test
  void nullTokenIsRejected() {
    assertThrows(JwtValidationException.class, () -> jwtService.verify(null));
  }

  @Test
  void expiredTokenKeepsItsMessage() throws Exception {
    String token = signed(body("1", OK_SUB, "[]", "\"j\""));
    JwtValidationException e =
        assertThrows(JwtValidationException.class, () -> jwtService.verify(token));
    assertEquals("Token suresi dolmus.", e.getMessage());
  }

  @Test
  void issuedTokenRoundTripsBackToTheSameClaims() {
    UUID userId = UUID.randomUUID();
    String token = jwtService.issueAccessToken(userId, List.of("USER"), "jti-1");

    DecodedJwt decoded = jwtService.verify(token);

    assertEquals(userId, decoded.userId());
    assertEquals(List.of("USER"), decoded.roles());
    assertEquals("jti-1", decoded.jti());
  }

  @Test
  void tamperedSignatureIsRejected() {
    String token = jwtService.issueAccessToken(UUID.randomUUID(), List.of("USER"), "jti-2");
    String[] parts = token.split("\\.");
    String tampered = parts[0] + "." + parts[1] + "." + flipInteriorChar(parts[2]);

    assertThrows(JwtValidationException.class, () -> jwtService.verify(tampered));
  }

  @Test
  void malformedTokenIsRejected() {
    assertThrows(JwtValidationException.class, () -> jwtService.verify("not-a-jwt"));
  }

  /**
   * Bilerek son karakteri DEGIL, ortadaki bir karakteri degistirir: base64url'de son karakter (imza
   * baytlarinin uzunlugu 3'e bolunmuyorsa) decoder'in yok saydigi padding bitlerine denk gelebilir
   * — bu durumda "A"<->"B" gibi bir degisiklik decode edilen baytlarda HICBIR FARK yaratmaz
   * (yaklasik %25 ihtimalle flaky test). Ortadaki bir karakter her zaman tam bir 4'lu grubun icinde
   * oldugundan degisikligi garanti eder.
   */
  private static String flipInteriorChar(String value) {
    int index = value.length() / 2;
    char current = value.charAt(index);
    char replacement = current == 'A' ? 'B' : 'A';
    return value.substring(0, index) + replacement + value.substring(index + 1);
  }
}
