package com.app.tracker.notification.email;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * {@code APP_PUBLIC_URL} e-postalardaki (dogrulama/sifirlama/gorev) linklerin temelidir. Bos veya
 * {@code localhost} iceren bir deger staging/prod'da ORTAM DEGISKENI UNUTULDUGU anlamina gelir —
 * fail-closed: JwtKeyProvider/WebhookSecretService ile AYNI desen ({@code staging}/{@code prod}
 * profilinde reddet, {@code migrate} bilerek muaf cunku o profilde e-posta bean'leri hic
 * olusturulmaz).
 */
@Component
public final class AppPublicUrlGuard {

  static final String LOCALHOST_MARKER = "localhost";

  public AppPublicUrlGuard(EmailProperties properties, Environment environment) {
    boolean protectedProfile = environment.acceptsProfiles(Profiles.of("staging", "prod"));
    String url = properties.getPublicUrl();
    boolean devLike = url == null || url.isBlank() || url.contains(LOCALHOST_MARKER);
    if (protectedProfile && devLike) {
      throw new IllegalStateException(
          "staging/prod profilinde APP_PUBLIC_URL bos veya localhost olamaz. Gercek alan adini "
              + "gosteren bir deger ayarlanmali, aksi halde e-postadaki dogrulama/sifirlama "
              + "linkleri kullanicinin makinesinde acilmaz.");
    }
  }
}
