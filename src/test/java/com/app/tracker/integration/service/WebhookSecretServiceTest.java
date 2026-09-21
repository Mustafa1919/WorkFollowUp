package com.app.tracker.integration.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.integration.WebhookProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Master key varsayilani PUBLIC repoda durur (bkz. WebhookSecretService javadoc): staging/prod'da
 * dev/kisa anahtarla acilis reddedilmezse, env degiskeni unutuldugunda herkesin turetebildigi
 * secret'larla sahte webhook (dolayisiyla sahte gorev durumu) kabul edilirdi. Docker/Spring context
 * gerektirmez.
 */
class WebhookSecretServiceTest {

  private static final String DEV_KEY = "dev-webhook-master-key-not-a-secret-0000";
  private static final String STRONG_KEY = "k3Jx9Vb2Qm7Zp1Lw8Yt4Nc6Rd0Hf5Sg3Ua9Ie2O";

  private static WebhookProperties properties(String key) {
    WebhookProperties properties = new WebhookProperties();
    properties.setSecretMasterKey(key);
    return properties;
  }

  private static MockEnvironment profiles(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @ParameterizedTest
  @ValueSource(strings = {"staging", "prod"})
  void devKeyIsRejectedInProtectedProfiles(String profile) {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new WebhookSecretService(properties(DEV_KEY), profiles(profile)));

    assertTrue(e.getMessage().contains("DEV webhook anahtari"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"staging", "prod"})
  void shortKeyIsRejectedInProtectedProfiles(String profile) {
    assertThrows(
        IllegalStateException.class,
        () -> new WebhookSecretService(properties("kisa-ama-dev-degil"), profiles(profile)));
  }

  @Test
  void copyingTheExamplePlaceholderVerbatimFailsStartup() {
    // k8s/overlays/*/.env.secret.example placeholder'i bilerek dev on ekiyle baslar.
    assertThrows(
        IllegalStateException.class,
        () -> new WebhookSecretService(properties("dev-webhook-REPLACE-ME"), profiles("prod")));
  }

  @Test
  void blankKeyIsAlwaysRejected() {
    assertThrows(
        IllegalStateException.class, () -> new WebhookSecretService(properties(""), profiles()));
    assertThrows(
        IllegalStateException.class, () -> new WebhookSecretService(properties(null), profiles()));
  }

  @Test
  void devKeyIsAllowedWithoutProtectedProfileAndStrongKeyEverywhere() {
    assertDoesNotThrow(() -> new WebhookSecretService(properties(DEV_KEY), profiles()));
    assertDoesNotThrow(() -> new WebhookSecretService(properties(DEV_KEY), profiles("dev")));
    assertDoesNotThrow(() -> new WebhookSecretService(properties(STRONG_KEY), profiles("prod")));
  }

  @Test
  void derivedSecretIsDeterministicAndBoundToEveryInput() {
    UUID id = UUID.randomUUID();
    WebhookSecretService service = new WebhookSecretService(properties(STRONG_KEY), profiles());

    String secret = service.deriveSecret(id, 1);

    assertEquals(secret, service.deriveSecret(id, 1));
    assertEquals(64, secret.length());
    assertNotEquals(secret, service.deriveSecret(id, 2), "rotasyon secret'i degistirmeli");
    assertNotEquals(secret, service.deriveSecret(UUID.randomUUID(), 1), "entegrasyona ozgu olmali");
    assertNotEquals(
        secret,
        new WebhookSecretService(properties(STRONG_KEY + "x"), profiles()).deriveSecret(id, 1),
        "master key degisince degismeli");
  }
}
