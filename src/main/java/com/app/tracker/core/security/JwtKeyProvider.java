package com.app.tracker.core.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.Getter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * RS256 anahtar ciftini PEM'den yukler. Gelistirme anahtarlari
 * src/main/resources/keys/dev-jwt-*.pem altinda commit edilmistir — bunlar GERCEK bir sir DEGILDIR,
 * sadece local calisma icindir; staging/prod, {@code app.jwt.*-key-location} ortam degiskenleriyle
 * gercek anahtarlarina (K8s secret dosyasi) isaret eder.
 *
 * <p>Dev anahtarlari PUBLIC repoda durdugu icin, {@code staging}/{@code prod} profilinde bu
 * anahtarlarla acilis REDDEDILIR (fail-closed): aksi halde ortam degiskeni unutulursa uygulama
 * sessizce herkesin bildigi anahtarla token imzalar/dogrular ve {@code roles} claim'i (SYSTEM_ADMIN
 * dahil) sahte uretilebilirdi. {@code migrate} profili bilerek muaf: migration Job'i JWT kullanmaz
 * ve anahtar mount'lanmaz.
 */
@Getter
@Component
public class JwtKeyProvider {

  private final PrivateKey privateKey;
  private final PublicKey publicKey;

  static final String DEV_KEY_MARKER = "dev-jwt-";

  public JwtKeyProvider(
      JwtProperties properties, ResourceLoader resourceLoader, Environment environment) {
    rejectDevKeysInProtectedProfiles(properties, environment);
    try {
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      this.privateKey =
          keyFactory.generatePrivate(
              new PKCS8EncodedKeySpec(
                  readDer(resourceLoader.getResource(properties.getPrivateKeyLocation()))));
      this.publicKey =
          keyFactory.generatePublic(
              new X509EncodedKeySpec(
                  readDer(resourceLoader.getResource(properties.getPublicKeyLocation()))));
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException("JWT RSA anahtarlari yuklenemedi", e);
    }
  }

  static void rejectDevKeysInProtectedProfiles(JwtProperties properties, Environment environment) {
    boolean protectedProfile = environment.acceptsProfiles(Profiles.of("staging", "prod"));
    boolean devKey =
        isDevKey(properties.getPrivateKeyLocation()) || isDevKey(properties.getPublicKeyLocation());
    if (protectedProfile && devKey) {
      throw new IllegalStateException(
          "staging/prod profilinde repodaki DEV JWT anahtarlari kullanilamaz. "
              + "JWT_PRIVATE_KEY_LOCATION ve JWT_PUBLIC_KEY_LOCATION gercek anahtar dosyalarina "
              + "isaret etmeli.");
    }
  }

  private static boolean isDevKey(String location) {
    return location == null || location.contains(DEV_KEY_MARKER);
  }

  private static byte[] readDer(Resource resource) throws IOException {
    String pem;
    try (InputStream in = resource.getInputStream()) {
      pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
    }
    String base64 =
        pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
            .replaceAll("-----END [A-Z ]+-----", "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(base64);
  }
}
