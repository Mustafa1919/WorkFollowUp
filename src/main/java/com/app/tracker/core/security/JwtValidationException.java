package com.app.tracker.core.security;

/** JWT imza/format/sure gecerliligi basarisiz oldugunda firlatilir. */
public class JwtValidationException extends RuntimeException {

  public JwtValidationException(String message) {
    super(message);
  }
}
