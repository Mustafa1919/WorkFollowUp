package com.app.tracker.notification.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Gercek {@link ResilientEmailSender}, kapali bir porta karsi (baglanti reddi = gecici hata) ve
 * gecersiz bicimli bir alici adresine karsi (kalici red) — {@code ResilientSlackSenderTest} ile
 * AYNI ilke: gonderici yalniz dayaniklilik kararlarini tasir, gercek SMTP saglayicisi davranisi
 * degil.
 */
class ResilientEmailSenderTest {

  private static EmailProperties properties(int window, int minCalls) {
    EmailProperties properties = new EmailProperties();
    properties.setFrom("no-reply@tracker.local");
    properties.setSlidingWindowSize(window);
    properties.setMinimumNumberOfCalls(minCalls);
    properties.setFailureRateThreshold(50f);
    properties.setOpenStateDuration(Duration.ofSeconds(30));
    properties.setConnectTimeout(Duration.ofMillis(500));
    properties.setReadTimeout(Duration.ofMillis(500));
    return properties;
  }

  private static JavaMailSenderImpl mailSender(int port) {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost("127.0.0.1");
    sender.setPort(port);
    return sender;
  }

  private static EmailContent content() {
    return new EmailContent("Konu", "Metin", "<p>Metin</p>");
  }

  @Test
  void invalidRecipientAddressIsPermanent() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ResilientEmailSender sender =
          new ResilientEmailSender(mailSender(socket.getLocalPort()), properties(10, 5));

      assertThrows(EmailPermanentException.class, () -> sender.send("gecersiz-adres", content()));
    }
  }

  @Test
  void silentServerTimesOutAsTransientInsteadOfBlocking() throws Exception {
    // Baglantiyi kabul eden (backlog) ama SMTP selamlamasi yollamayan sunucu: zaman asimi
    // uygulanmasaydi cagri sonsuza dek beklerdi.
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ResilientEmailSender sender =
          new ResilientEmailSender(mailSender(socket.getLocalPort()), properties(10, 5));

      long start = System.nanoTime();
      assertThrows(EmailTransientException.class, () -> sender.send("user@example.com", content()));
      assertTrue(
          Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0,
          "okuma zaman asimi (500 ms) uygulanmali");
    }
  }

  @Test
  void connectionRefusedIsTransient() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    ResilientEmailSender sender =
        new ResilientEmailSender(mailSender(closedPort), properties(10, 5));

    assertThrows(EmailTransientException.class, () -> sender.send("user@example.com", content()));
  }

  @Test
  void breakerOpensAfterRepeatedTransientFailures() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    ResilientEmailSender sender =
        new ResilientEmailSender(mailSender(closedPort), properties(4, 4));

    for (int i = 0; i < 4; i++) {
      assertThrows(EmailTransientException.class, () -> sender.send("user@example.com", content()));
    }
    assertEquals(CircuitBreaker.State.OPEN, sender.circuitBreaker().getState());

    EmailTransientException e =
        assertThrows(
            EmailTransientException.class, () -> sender.send("user@example.com", content()));
    assertTrue(e.getMessage().contains("devre kesici"));
  }
}
