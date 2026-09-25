package com.app.tracker.notification.email;

/** SMTP baglanti/zaman asimi gibi gecici hatalar — Kafka error handler yeniden dener. */
public class EmailTransientException extends RuntimeException {

  public EmailTransientException(String message) {
    super(message);
  }

  public EmailTransientException(String message, Throwable cause) {
    super(message, cause);
  }
}
