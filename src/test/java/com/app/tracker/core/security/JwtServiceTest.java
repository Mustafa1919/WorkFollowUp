package com.app.tracker.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Docker/Spring context gerektirmeyen tek gercek dogrulama: hand-rolled RS256 imzalamanin (bkz.
 * JwtService) gercekten calistigini kanitlar. Dev PEM anahtarlariyla gercek bir imzala/dogrula turu
 * yapar.
 */
class JwtServiceTest {

  private final JwtService jwtService = newJwtService();

  private static JwtService newJwtService() {
    JwtProperties properties = new JwtProperties();
    properties.setPrivateKeyLocation("classpath:keys/dev-jwt-private.pem");
    properties.setPublicKeyLocation("classpath:keys/dev-jwt-public.pem");
    JwtKeyProvider keyProvider = new JwtKeyProvider(properties, new DefaultResourceLoader());
    ObjectMapper objectMapper = JsonMapper.builder().build();
    return new JwtService(keyProvider, properties, objectMapper);
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
