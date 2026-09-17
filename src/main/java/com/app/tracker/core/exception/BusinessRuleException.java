package com.app.tracker.core.exception;

/** SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 2.1 — is kurali ihlallerinde firlatilir (400). */
public class BusinessRuleException extends RuntimeException {

  public BusinessRuleException(String message) {
    super(message);
  }
}
