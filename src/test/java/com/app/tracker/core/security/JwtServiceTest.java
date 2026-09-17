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
    String tampered = parts[0] + "." + parts[1] + "." + flipLastChar(parts[2]);

    assertThrows(JwtValidationException.class, () -> jwtService.verify(tampered));
  }

  @Test
  void malformedTokenIsRejected() {
    assertThrows(JwtValidationException.class, () -> jwtService.verify("not-a-jwt"));
  }

  private static String flipLastChar(String value) {
    char last = value.charAt(value.length() - 1);
    char replacement = last == 'A' ? 'B' : 'A';
    return value.substring(0, value.length() - 1) + replacement;
  }
}
