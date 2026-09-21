package com.app.tracker.integration.service;

import com.app.tracker.integration.WebhookProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Entegrasyon basina HMAC secret'ini uygulama anahtarindan TUREtir: {@code HMAC-SHA256(masterKey,
 * "webhook-secret:v1:" + integrationId + ":" + secretVersion)}. Secret DB'de saklanmadigi icin DB
 * sizintisi imza secret'larini aciga cikarmaz; rotasyon {@code secret_version}'i artirmaktir.
 * Bedel: master key degisirse TUM entegrasyonlarin secret'i degisir (GitHub tarafinda yeniden
 * girilmesi gerekir) — master key rotasyonu bu yuzden nadir ve planli bir operasyondur.
 *
 * <p>Master key'in varsayilani PUBLIC repoda durur (yerel calisma icin); JwtKeyProvider ile ayni
 * gerekceyle {@code staging}/{@code prod} profilinde dev anahtariyla (veya kisa/eksik anahtarla)
 * acilis REDDEDILIR: aksi halde env degiskeni unutulunca herkesin bildigi anahtardan turetilen
 * secret'larla sahte webhook (dolayisiyla sahte gorev durumu) uretilebilirdi. {@code migrate}
 * profili muaf: migration Job'i webhook kullanmaz ve anahtar almaz.
 */
@Component
@Profile("!migrate")
public final class WebhookSecretService {

  static final int MIN_PROTECTED_KEY_LENGTH = 32;
  private static final String ALGORITHM = "HmacSHA256";

  private final SecretKeySpec masterKey;

  public WebhookSecretService(WebhookProperties properties, Environment environment) {
    String key = properties.getSecretMasterKey();
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("app.webhook.secret-master-key bos olamaz.");
    }
    rejectWeakKeyInProtectedProfiles(key, environment);
    this.masterKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  static void rejectWeakKeyInProtectedProfiles(String key, Environment environment) {
    boolean protectedProfile = environment.acceptsProfiles(Profiles.of("staging", "prod"));
    if (protectedProfile
        && (key.startsWith(WebhookProperties.DEV_KEY_MARKER)
            || key.length() < MIN_PROTECTED_KEY_LENGTH)) {
      throw new IllegalStateException(
          "staging/prod profilinde repodaki DEV webhook anahtari (veya "
              + MIN_PROTECTED_KEY_LENGTH
              + " karakterden kisa bir anahtar) kullanilamaz. WEBHOOK_SECRET_MASTER_KEY gercek, "
              + "rastgele bir degere ayarlanmali.");
    }
  }

  /**
   * Ayni master key'den AMAC etiketiyle ayrilmis 256 bit anahtar (domain separation): {@code
   * HMAC-SHA256(masterKey, "key:v1:" + purpose)}. Farkli amaclar (ornegin Slack adresi sifreleme)
   * birbirinin anahtarini veya webhook secret'larini uretemez; dev/eksik anahtar guard'i bu sinifin
   * kurucusunda tek yerde durur.
   */
  public byte[] deriveKey(String purpose) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(masterKey);
      return mac.doFinal(("key:v1:" + purpose).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Anahtar turetilemedi: " + purpose, e);
    }
  }

  /** GitHub webhook ayarina girilecek secret (64 hex karakter). */
  public String deriveSecret(UUID integrationId, int secretVersion) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(masterKey);
      byte[] derived =
          mac.doFinal(
              ("webhook-secret:v1:" + integrationId + ":" + secretVersion)
                  .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(derived);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Webhook secret'i turetilemedi", e);
    }
  }
}
