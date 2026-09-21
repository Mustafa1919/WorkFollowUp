package com.app.tracker.notification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.app.tracker.core.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF savunmasinin tek dayanagi allow-list'tir: yonetici sunucuyu istedigi adrese POST ettiremez.
 * Her satir, ayristirici farklarindan yararlanan bilinen bir atlatma denemesidir.
 */
class SlackWebhookUrlPolicyTest {

  private static final String VALID =
      "https://hooks.slack.com/services/T0123ABC/B0456DEF/xYz789TokenValue";

  @Test
  void acceptsTheSlackIncomingWebhookShape() {
    assertEquals(VALID, SlackWebhookUrlPolicy.validate(VALID).toString());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "http://hooks.slack.com/services/T1/B2/abc", // https degil
        "https://hooks.slack.com.evil.com/services/T1/B2/abc", // alt alan adi tuzagi
        "https://evil.com/hooks.slack.com/services/T1/B2/abc",
        "https://hooks.slack.com@evil.com/services/T1/B2/abc", // userinfo: host evil.com
        "https://evil.com@hooks.slack.com/services/T1/B2/abc", // userinfo ile hooks.slack.com
        "https://hooks.slack.com:8443/services/T1/B2/abc", // port
        "https://hooks.slack.com:443/services/T1/B2/abc", // acik port bile kabul edilmez
        "https://HOOKS.SLACK.COM/services/T1/B2/abc", // buyuk harf: bicim birebir
        "https://hooks.slack.com/services/T1/B2/abc?x=1", // sorgu
        "https://hooks.slack.com/services/T1/B2/abc#frag", // parca
        "https://hooks.slack.com/services/T1/B2/abc/extra", // ek segment
        "https://hooks.slack.com/services/T1/B2", // eksik segment
        "https://hooks.slack.com/services/T1/B2/", // bos segment
        "https://hooks.slack.com/services/../../etc/passwd",
        "https://hooks.slack.com/services/T1/B2/abc%2F..", // kodlanmis yol
        "https://hooks.slack.com/services/T1/B2/abc\n", // sondaki satir sonu ($ tuzagi)
        "https://hooks.slack.com/services/T1/B2/abc\r\nHost: evil.com",
        "https://hooks.slack.com/services/T1/B2/abc ",
        " https://hooks.slack.com/services/T1/B2/abc",
        "https://127.0.0.1/services/T1/B2/abc",
        "https://localhost/services/T1/B2/abc",
        "https://[::1]/services/T1/B2/abc",
        "file:///etc/passwd",
        "ftp://hooks.slack.com/services/T1/B2/abc",
        "//hooks.slack.com/services/T1/B2/abc",
        "hooks.slack.com/services/T1/B2/abc"
      })
  void rejectsEverythingElse(String candidate) {
    assertThrows(BusinessRuleException.class, () -> SlackWebhookUrlPolicy.validate(candidate));
  }

  @Test
  void rejectsOverlongInputEvenIfShapeMatches() {
    String longToken = "https://hooks.slack.com/services/T1/B2/" + "a".repeat(200);
    assertThrows(BusinessRuleException.class, () -> SlackWebhookUrlPolicy.validate(longToken));
    // Sinirin tam altindaki uzunluk gecerli.
    String atLimit =
        ("https://hooks.slack.com/services/T1/B2/")
            + "a"
                .repeat(
                    SlackWebhookUrlPolicy.MAX_LENGTH
                        - "https://hooks.slack.com/services/T1/B2/".length());
    assertDoesNotThrow(() -> SlackWebhookUrlPolicy.validate(atLimit));
  }

  @Test
  void errorMessageNeverEchoesTheRejectedValue() {
    String secretLooking = "https://evil.example/services/T1/B2/SUPER-SECRET-TOKEN";
    BusinessRuleException e =
        assertThrows(
            BusinessRuleException.class, () -> SlackWebhookUrlPolicy.validate(secretLooking));
    assertFalse(e.getMessage().contains("SUPER-SECRET-TOKEN"));
    assertFalse(e.getMessage().contains("evil.example"));
  }
}
