package com.app.tracker.notification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.notification.SlackProperties;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Gercek {@link ResilientSlackSender}, yerel bir JDK HttpServer'a karsi (yeni bagimlilik yok).
 * Adres politikasi burada UYGULANMAZ (o, SlackIntegrationService'in isi); gonderici yalniz HTTP
 * davranisini ve dayaniklilik kararlarini tasir.
 */
class ResilientSlackSenderTest {

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<String> contentTypes = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private volatile long delayMillis = 0;
  private volatile String redirectTo = null;
  private final AtomicInteger redirectTargetHits = new AtomicInteger();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          hits.incrementAndGet();
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
          if (delayMillis > 0) {
            try {
              Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          if (redirectTo != null) {
            exchange.getResponseHeaders().add("Location", redirectTo);
            exchange.sendResponseHeaders(302, -1);
          } else {
            exchange.sendResponseHeaders(status, -1);
          }
          exchange.close();
        });
    server.createContext(
        "/redirect-target",
        exchange -> {
          redirectTargetHits.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private URI hook() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
  }

  private static SlackProperties properties(int window, int minCalls, Duration readTimeout) {
    SlackProperties properties = new SlackProperties();
    properties.setSlidingWindowSize(window);
    properties.setMinimumNumberOfCalls(minCalls);
    properties.setFailureRateThreshold(50f);
    properties.setOpenStateDuration(Duration.ofSeconds(30));
    properties.setReadTimeout(readTimeout);
    properties.setConnectTimeout(Duration.ofSeconds(1));
    return properties;
  }

  private ResilientSlackSender sender(int window, int minCalls) {
    return new ResilientSlackSender(
        properties(window, minCalls, Duration.ofSeconds(2)), new ObjectMapper());
  }

  @Test
  void postsJsonTextBodyAndSucceedsOn2xx() {
    ResilientSlackSender sender = sender(10, 5);

    assertDoesNotThrow(() -> sender.send(hook(), "hello \"world\""));

    assertEquals(1, hits.get());
    assertEquals("{\"text\":\"hello \\\"world\\\"\"}", bodies.get(0), "JSON, kacislanmis");
    assertTrue(contentTypes.get(0).startsWith("application/json"));
  }

  @Test
  void serverErrorsAndRateLimitAreTransient() {
    ResilientSlackSender sender = sender(100, 100);

    for (int code : new int[] {429, 500, 502, 503}) {
      status = code;
      assertThrows(SlackTransientException.class, () -> sender.send(hook(), "x"), "HTTP " + code);
    }
  }

  @Test
  void clientErrorsArePermanentAndNeverOpenTheBreaker() {
    ResilientSlackSender sender = sender(4, 2);
    status = 404;

    for (int i = 0; i < 10; i++) {
      assertThrows(SlackPermanentException.class, () -> sender.send(hook(), "x"));
    }

    assertEquals(CircuitBreaker.State.CLOSED, sender.circuitBreaker().getState());
    assertEquals(10, hits.get(), "kapali devre: her cagri gercekten yapilmali");
  }

  @Test
  void otherClientErrorCodesArePermanentToo() {
    ResilientSlackSender sender = sender(100, 100);
    for (int code : new int[] {400, 403, 410}) {
      status = code;
      assertThrows(SlackPermanentException.class, () -> sender.send(hook(), "x"), "HTTP " + code);
    }
  }

  @Test
  void redirectsAreNotFollowedAndAreTreatedAsPermanent() {
    ResilientSlackSender sender = sender(10, 5);
    redirectTo = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target";

    assertThrows(SlackPermanentException.class, () -> sender.send(hook(), "x"));

    assertEquals(0, redirectTargetHits.get(), "yonlendirme takip edilmemeli (SSRF)");
  }

  @Test
  void readTimeoutIsTransient() {
    ResilientSlackSender sender =
        new ResilientSlackSender(properties(10, 5, Duration.ofMillis(200)), new ObjectMapper());
    delayMillis = 1500;

    assertThrows(SlackTransientException.class, () -> sender.send(hook(), "x"));
  }

  @Test
  void connectionRefusedIsTransient() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      closedPort = socket.getLocalPort();
    }
    ResilientSlackSender sender = sender(10, 5);

    SlackTransientException e =
        assertThrows(
            SlackTransientException.class,
            () -> sender.send(URI.create("http://127.0.0.1:" + closedPort + "/hook"), "x"));
    assertFalse(e.getMessage().contains("/hook"), "hata mesaji adresi sizdirmamali");
  }

  @Test
  void breakerOpensAfterRepeatedTransientFailuresAndStopsCallingSlack() {
    ResilientSlackSender sender = sender(4, 4);
    status = 500;

    for (int i = 0; i < 4; i++) {
      assertThrows(SlackTransientException.class, () -> sender.send(hook(), "x"));
    }
    assertEquals(CircuitBreaker.State.OPEN, sender.circuitBreaker().getState());
    int hitsWhenOpened = hits.get();

    SlackTransientException e =
        assertThrows(SlackTransientException.class, () -> sender.send(hook(), "x"));

    assertEquals(hitsWhenOpened, hits.get(), "acik devre: Slack'e HIC cagri yapilmamali");
    assertTrue(e.getMessage().contains("devre kesici"));
  }

  @Test
  void breakerRecoversThroughHalfOpenWhenSlackIsBackUp() {
    SlackProperties properties = properties(4, 4, Duration.ofSeconds(2));
    properties.setOpenStateDuration(Duration.ofMillis(300));
    properties.setPermittedCallsInHalfOpenState(1);
    ResilientSlackSender sender = new ResilientSlackSender(properties, new ObjectMapper());
    status = 500;
    for (int i = 0; i < 4; i++) {
      assertThrows(SlackTransientException.class, () -> sender.send(hook(), "x"));
    }
    assertEquals(CircuitBreaker.State.OPEN, sender.circuitBreaker().getState());

    status = 200;
    long deadline = System.currentTimeMillis() + 5_000;
    boolean recovered = false;
    while (System.currentTimeMillis() < deadline && !recovered) {
      try {
        sender.send(hook(), "probe");
        recovered = true;
      } catch (SlackTransientException stillOpen) {
        sleep(100);
      }
    }

    assertTrue(recovered, "acilma suresi dolunca deneme cagrisi gecmeli");
    assertEquals(CircuitBreaker.State.CLOSED, sender.circuitBreaker().getState());
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
