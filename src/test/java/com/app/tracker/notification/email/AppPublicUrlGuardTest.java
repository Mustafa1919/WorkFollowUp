package com.app.tracker.notification.email;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * JwtKeyProviderTest ile AYNI desen: dev-benzeri bir APP_PUBLIC_URL degeri staging/prod'da ortam
 * degiskeninin UNUTULDUGU anlamina gelir, fail-closed reddedilmeli.
 */
class AppPublicUrlGuardTest {

  private static EmailProperties properties(String publicUrl) {
    EmailProperties properties = new EmailProperties();
    properties.setPublicUrl(publicUrl);
    return properties;
  }

  private static MockEnvironment profiles(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"http://localhost:5173", "http://localhost", " "})
  void devLikeUrlsAreRejectedInProtectedProfiles(String url) {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new AppPublicUrlGuard(properties(url), profiles("prod")));
    assertTrue(e.getMessage().contains("APP_PUBLIC_URL"));
  }

  @Test
  void realUrlIsAcceptedInProd() {
    assertDoesNotThrow(
        () -> new AppPublicUrlGuard(properties("https://app.example.com"), profiles("prod")));
  }

  @Test
  void devLikeUrlStillWorksOutsideProtectedProfiles() {
    assertDoesNotThrow(
        () -> new AppPublicUrlGuard(properties("http://localhost:5173"), profiles("dev")));
  }

  @Test
  void devLikeUrlStillWorksWithoutAnyActiveProfile() {
    assertDoesNotThrow(
        () -> new AppPublicUrlGuard(properties("http://localhost:5173"), profiles()));
  }
}
