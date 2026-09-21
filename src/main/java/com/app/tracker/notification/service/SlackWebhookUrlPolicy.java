package com.app.tracker.notification.service;

import com.app.tracker.core.exception.BusinessRuleException;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * Yonetici tarafindan verilen adresin sunucunun POST edecegi TEK yer olmasi SSRF yuzeyidir (OWASP
 * 2025: Broken Access Control semsiyesi). Serbest URL kabul edip "ic ag adreslerini engelle" (deny
 * list) YETMEZ: DNS rebinding, yonlendirme, {@code http://0x7f000001}, IPv6 bicimleri vb. Bu yuzden
 * ALLOW-LIST: yalniz Slack'in gelen-webhook bicimi kabul edilir.
 *
 * <p>Bilerek URL AYRISTIRICISINA guvenilmez: tum dizge tek bir regex ile tam eslesmek zorundadir
 * ({@code matches()}; {@code $} kullanilmaz, cunku sondaki satir sonunu tolere eder). Boylece
 * {@code https://hooks.slack.com@evil.com/...} (userinfo), {@code hooks.slack.com.evil.com},
 * sorgu/parca ve port oyunlari ayristirici farklarina dusmeden reddedilir. Yonlendirmeler ayrica
 * gonderim katmaninda takip edilmez (bkz. ResilientSlackSender).
 *
 * <p>Teams/baska saglayicilar bu dilimin kapsami disindadir; yeni saglayici = yeni politika.
 */
public final class SlackWebhookUrlPolicy {

  static final int MAX_LENGTH = 200;

  private static final Pattern SLACK_INCOMING_WEBHOOK =
      Pattern.compile(
          "https://hooks\\.slack\\.com/services/[A-Za-z0-9]+/[A-Za-z0-9]+/[A-Za-z0-9]+");

  private SlackWebhookUrlPolicy() {}

  /**
   * @throws BusinessRuleException adres Slack incoming-webhook bicimine uymuyorsa. Hata mesaji
   *     girilen degeri ICERMEZ (kimlik bilgisi log'a/yanita sizmasin).
   */
  public static URI validate(String raw) {
    if (raw == null
        || raw.length() > MAX_LENGTH
        || !SLACK_INCOMING_WEBHOOK.matcher(raw).matches()) {
      throw new BusinessRuleException(
          "Gecersiz Slack webhook adresi: https://hooks.slack.com/services/T.../B.../... "
              + "bicimi bekleniyor.");
    }
    return URI.create(raw);
  }
}
