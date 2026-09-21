package com.app.tracker.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.app.tracker.integration.WebhookProperties;
import com.app.tracker.integration.service.WebhookSecretService;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Sifreleme; Slack adresi bir kimlik bilgisidir. Docker/Spring context gerektirmez. */
class SlackUrlCipherTest {

  private static final String KEY_A = "a".repeat(40); // dusuk entropi: gitleaks yanlis pozitifi
  private static final String KEY_B = "b".repeat(40);
  private static final String URL = "https://hooks.slack.com/services/T1/B2/SuperSecretToken";

  private static SlackUrlCipher cipher(String masterKey) {
    WebhookProperties properties = new WebhookProperties();
    properties.setSecretMasterKey(masterKey);
    return new SlackUrlCipher(new WebhookSecretService(properties, new MockEnvironment()));
  }

  @Test
  void roundTripsAndNeverStoresPlaintext() {
    UUID workspace = UUID.randomUUID();
    SlackUrlCipher cipher = cipher(KEY_A);

    String stored = cipher.encrypt(workspace, URL);

    assertEquals(URL, cipher.decrypt(workspace, stored));
    assertFalse(stored.contains("SuperSecretToken"));
    assertFalse(stored.contains("hooks.slack.com"));
    assertEquals(true, stored.startsWith("v1."));
  }

  @Test
  void sameInputEncryptsDifferentlyEachTime() {
    UUID workspace = UUID.randomUUID();
    SlackUrlCipher cipher = cipher(KEY_A);

    assertNotEquals(
        cipher.encrypt(workspace, URL),
        cipher.encrypt(workspace, URL),
        "IV tekrar ederse GCM guvenligi coker");
  }

  @Test
  void ciphertextCopiedToAnotherTenantCannotBeDecrypted() {
    SlackUrlCipher cipher = cipher(KEY_A);
    String stored = cipher.encrypt(UUID.randomUUID(), URL);

    assertThrows(
        IllegalStateException.class,
        () -> cipher.decrypt(UUID.randomUUID(), stored),
        "AAD = tenant");
  }

  @Test
  void differentMasterKeyCannotDecrypt() {
    UUID workspace = UUID.randomUUID();
    String stored = cipher(KEY_A).encrypt(workspace, URL);

    assertThrows(IllegalStateException.class, () -> cipher(KEY_B).decrypt(workspace, stored));
  }

  @Test
  void tamperedOrMalformedValuesFailLoudlyInsteadOfSilently() {
    UUID workspace = UUID.randomUUID();
    SlackUrlCipher cipher = cipher(KEY_A);
    String stored = cipher.encrypt(workspace, URL);
    String[] parts = stored.substring(3).split("\\.");
    String flipped = parts[1].substring(0, 4).equals("AAAA") ? "BBBB" : "AAAA";
    String tampered = "v1." + parts[0] + "." + flipped + parts[1].substring(4);

    assertThrows(IllegalStateException.class, () -> cipher.decrypt(workspace, tampered));
    assertThrows(IllegalStateException.class, () -> cipher.decrypt(workspace, "v2." + parts[0]));
    assertThrows(IllegalStateException.class, () -> cipher.decrypt(workspace, "v1.onlyonepart"));
    assertThrows(IllegalStateException.class, () -> cipher.decrypt(workspace, "v1.!!!.???"));
    assertThrows(IllegalStateException.class, () -> cipher.decrypt(workspace, null));
  }

  @Test
  void derivedKeysAreSeparatedByPurpose() {
    WebhookProperties properties = new WebhookProperties();
    properties.setSecretMasterKey(KEY_A);
    WebhookSecretService secrets = new WebhookSecretService(properties, new MockEnvironment());

    byte[] one = secrets.deriveKey("purpose-one");
    byte[] two = secrets.deriveKey("purpose-two");

    assertEquals(32, one.length);
    assertFalse(Arrays.equals(one, two), "farkli amaclar ayni anahtari uretmemeli");
    assertEquals(true, Arrays.equals(one, secrets.deriveKey("purpose-one")), "deterministik");
  }
}
