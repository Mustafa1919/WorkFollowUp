package com.app.tracker.notification.service;

/**
 * Gecici gonderim hatasi (ag, zaman asimi, 429, 5xx, acik devre kesici): yeniden denemek
 * anlamlidir. Kafka {@code DefaultErrorHandler}'a birakilir (ustel geri cekilme, sonra DLT) —
 * zincirde TEK retry sorumlusu odur, gondericinin kendi retry'i yoktur.
 */
public class SlackTransientException extends RuntimeException {

  public SlackTransientException(String message) {
    super(message);
  }

  public SlackTransientException(String message, Throwable cause) {
    super(message, cause);
  }
}
