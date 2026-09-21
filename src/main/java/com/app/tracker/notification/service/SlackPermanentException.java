package com.app.tracker.notification.service;

/**
 * Kalici gonderim hatasi (Slack 4xx: iptal edilmis/silinmis adres, arsivlenmis kanal, gecersiz
 * govde; beklenmeyen yonlendirme): yeniden denemek ise yaramaz. Devre kesiciyi TETIKLEMEZ (tek bir
 * tenant'in olu adresi, herkesin bildirimini kesmemeli).
 */
public class SlackPermanentException extends RuntimeException {

  public SlackPermanentException(String message) {
    super(message);
  }
}
