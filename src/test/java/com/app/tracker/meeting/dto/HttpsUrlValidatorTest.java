package com.app.tracker.meeting.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpsUrlValidatorTest {

  private final HttpsUrlValidator validator = new HttpsUrlValidator();

  @Test
  void nullAndBlankAreValid() {
    assertThat(validator.isValid(null, null)).isTrue();
    assertThat(validator.isValid("", null)).isTrue();
    assertThat(validator.isValid("   ", null)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://meet.example.com/x",
        "https://meet.example.com/y?room=1",
        "HTTPS://Meet.Example.com/x",
        "  https://meet.example.com/x  "
      })
  void acceptsHttps(String url) {
    assertThat(validator.isValid(url, null)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:alert(1)",
        "JaVaScRiPt:alert(1)",
        "data:text/html,<script>alert(1)</script>",
        "vbscript:msgbox(1)",
        "http://meet.example.com/x",
        "/toplanti",
        "meet.example.com/x",
        "//meet.example.com/x",
        "https://",
        "https:///x",
        "https://meet.example.com/a b",
        "https://meet.example.com/\u0000",
        "https://meet.example.com/ x"
      })
  void rejectsOthers(String url) {
    assertThat(validator.isValid(url, null)).isFalse();
  }
}
