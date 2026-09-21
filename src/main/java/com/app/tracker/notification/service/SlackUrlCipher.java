package com.app.tracker.notification.service;

import com.app.tracker.integration.service.WebhookSecretService;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Slack webhook adresini DB'de sifreli tutar: AES-256-GCM, kayit basina rastgele 96 bit IV, AAD =
 * workspace id'si (sifreli metin baska tenant'in satirina kopyalanirsa kimlik dogrulamasi patlar).
 * Anahtar, {@link WebhookSecretService#deriveKey} ile webhook master key'inden AYRI bir amac
 * etiketiyle turetilir; dev/eksik anahtar guard'i orada tek yerde durur.
 *
 * <p>Bedel: master key degisirse kayitli adresler COZULEMEZ (yonetici adresi yeniden girer); {@code
 * v1.} on eki, ileride sifreleme surumu degistirmek icindir. Cozme hatasi, sessizce "bildirim yok"
 * demek yerine {@link IllegalStateException} firlatir: bozuk anahtar/veri fark edilmelidir.
 */
@Component
@Profile("!migrate")
public class SlackUrlCipher {

  private static final String PREFIX = "v1.";
  private static final String KEY_PURPOSE = "slack-url-encryption";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public SlackUrlCipher(WebhookSecretService secretService) {
    this.key = new SecretKeySpec(secretService.deriveKey(KEY_PURPOSE), "AES");
  }

  public String encrypt(UUID workspaceId, String plaintext) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad(workspaceId));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
      return PREFIX + encoder.encodeToString(iv) + "." + encoder.encodeToString(ciphertext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Slack adresi sifrelenemedi", e);
    }
  }

  public String decrypt(UUID workspaceId, String stored) {
    try {
      if (stored == null || !stored.startsWith(PREFIX)) {
        throw new IllegalStateException("Taninmayan sifreli Slack adresi bicimi");
      }
      String[] parts = stored.substring(PREFIX.length()).split("\\.", -1);
      if (parts.length != 2) {
        throw new IllegalStateException("Bozuk sifreli Slack adresi");
      }
      Base64.Decoder decoder = Base64.getUrlDecoder();
      byte[] iv = decoder.decode(parts[0]);
      byte[] ciphertext = decoder.decode(parts[1]);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad(workspaceId));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Slack adresi cozulemedi (anahtar/tenant uyusmuyor)", e);
    }
  }

  private static byte[] aad(UUID workspaceId) {
    return workspaceId.toString().getBytes(StandardCharsets.UTF_8);
  }
}
