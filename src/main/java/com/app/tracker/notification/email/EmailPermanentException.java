package com.app.tracker.notification.email;

/** Kalici SMTP reddi (ör. gecersiz alici adresi) — serviste yutulur, DLT'ye gitmez. */
public class EmailPermanentException extends RuntimeException {

  public EmailPermanentException(String message) {
    super(message);
  }

  public EmailPermanentException(String message, Throwable cause) {
    super(message, cause);
  }
}
