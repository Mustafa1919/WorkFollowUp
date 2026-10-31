package com.app.tracker.meeting.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;
import java.net.URISyntaxException;

public class HttpsUrlValidator implements ConstraintValidator<HttpsUrl, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      return true;
    }
    // Kontrol karakteri ham değerde aranır: trim() uçlardaki \u0000'ı sessizce silerdi.
    if (value.chars().anyMatch(Character::isISOControl)) {
      return false;
    }
    String trimmed = value.trim();
    if (trimmed
        .codePoints()
        .anyMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
      return false;
    }
    try {
      URI uri = new URI(trimmed);
      String host = uri.getHost();
      return "https".equalsIgnoreCase(uri.getScheme()) && host != null && !host.isEmpty();
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
