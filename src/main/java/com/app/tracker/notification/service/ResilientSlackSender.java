package com.app.tracker.notification.service;

import com.app.tracker.notification.SlackProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 3 — Slack cagrisi Resilience4j devre kesicisi arkasinda.
 * Dayaniklilik kararlari (kaynak: kod yazma referansi Bolum 8):
 *
 * <ul>
 *   <li><b>Zaman asimi:</b> baglanti + okuma sinirli; asilirsa gecici hata.
 *   <li><b>Devre kesici (tek, global):</b> Slack tum tenant'lar icin ayni bagimlilik. Yalniz {@link
 *       SlackTransientException} sayilir; {@link SlackPermanentException} (tek tenant'in olu
 *       adresi) yok sayilir. Acikken cagri HIC yapilmaz ve gecici hata firlatilir.
 *   <li><b>Retry yok, bilerek:</b> zincirde tek retry sorumlusu Kafka error handler'idir (ustel
 *       geri cekilme + DLT). Burada ikinci bir retry katmani deneme sayisini carpardi.
 *   <li><b>Bulkhead yok, bilerek:</b> tek consumer thread'i zaten es zamanli cagriyi 1 ile
 *       sinirlar.
 *   <li><b>Yonlendirme takip edilmez</b> ({@code Redirect.NEVER}): adres allow-list'ten gecse bile
 *       3xx ile baska yere sicrama SSRF yolu olurdu; 3xx kalici hata sayilir.
 * </ul>
 *
 * Adres log'a YAZILMAZ (kimlik bilgisi).
 */
@Component
@Profile("!migrate")
public class ResilientSlackSender implements SlackSender {

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final CircuitBreaker circuitBreaker;

  public ResilientSlackSender(SlackProperties properties, ObjectMapper objectMapper) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.getConnectTimeout())
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getReadTimeout());
    this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    this.objectMapper = objectMapper;
    this.circuitBreaker =
        CircuitBreaker.of(
            "slack",
            CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.getSlidingWindowSize())
                .minimumNumberOfCalls(properties.getMinimumNumberOfCalls())
                .failureRateThreshold(properties.getFailureRateThreshold())
                .waitDurationInOpenState(properties.getOpenStateDuration())
                .permittedNumberOfCallsInHalfOpenState(
                    properties.getPermittedCallsInHalfOpenState())
                .recordExceptions(SlackTransientException.class)
                .ignoreExceptions(SlackPermanentException.class)
                .build());
  }

  @Override
  public void send(URI webhookUrl, String text) {
    String body = objectMapper.writeValueAsString(Map.of("text", text));
    try {
      circuitBreaker.executeRunnable(() -> post(webhookUrl, body));
    } catch (CallNotPermittedException e) {
      throw new SlackTransientException("Slack devre kesici acik, cagri yapilmadi", e);
    }
  }

  CircuitBreaker circuitBreaker() {
    return circuitBreaker;
  }

  private void post(URI webhookUrl, String body) {
    int status;
    try {
      status =
          restClient
              .post()
              .uri(webhookUrl)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .exchange((request, response) -> response.getStatusCode().value());
    } catch (RestClientException e) {
      // Ag hatasi / zaman asimi: hata mesaji adresi icerebilir, bu yuzden yalniz sinif adi.
      throw new SlackTransientException("Slack'e ulasilamadi: " + e.getClass().getSimpleName(), e);
    }
    if (status >= 200 && status < 300) {
      return;
    }
    if (status == 429 || status >= 500) {
      throw new SlackTransientException("Slack gecici hata: HTTP " + status);
    }
    throw new SlackPermanentException("Slack istegi reddetti: HTTP " + status);
  }
}
