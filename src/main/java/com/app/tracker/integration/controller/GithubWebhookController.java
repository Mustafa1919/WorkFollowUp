package com.app.tracker.integration.controller;

import com.app.tracker.integration.WebhookProperties;
import com.app.tracker.integration.service.WebhookIngestionService;
import com.app.tracker.integration.service.WebhookIngestionService.Outcome;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PHASE_3_DETAILED_DESIGN.md Bolum 2 — GitHub webhook ucu. JWT ile KORUNMAZ ({@code SecurityConfig}
 * bu yolu {@code permitAll} yapar): kimlik dogrulamasi HMAC imzasidir. Path'teki entegrasyon id'si
 * tenant'i ve imza secret'ini cozer (bkz. V13 migration'indaki tasarim notu).
 *
 * <p>Govde {@code @RequestBody} yerine dogrudan akistan, SINIRLI okunur: imza HAM bayt dizisi
 * uzerinden hesaplanir (Jackson'in yeniden serilestirmesi imzayi bozardi) ve imza dogrulanmadan
 * ONCE bellekte sinirsiz govde tutulmamalidir (kimliksiz uc). Yalniz {@code application/json} kabul
 * edilir; GitHub'in form-encoded secenegi ({@code payload=...}) desteklenmez.
 */
@RestController
@Profile("!migrate")
public class GithubWebhookController {

  private final WebhookIngestionService ingestionService;
  private final long maxBodyBytes;

  public GithubWebhookController(
      WebhookIngestionService ingestionService, WebhookProperties properties) {
    this.ingestionService = ingestionService;
    this.maxBodyBytes = properties.getMaxBodySize().toBytes();
  }

  @PostMapping(
      path = "/api/v1/webhooks/github/{integrationId}",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> receive(
      @PathVariable UUID integrationId,
      @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
      @RequestHeader(name = "X-GitHub-Event", required = false) String githubEvent,
      @RequestHeader(name = "X-GitHub-Delivery", required = false) String deliveryId,
      HttpServletRequest request)
      throws IOException {
    if (request.getContentLengthLong() > maxBodyBytes) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }
    // Content-Length yalan/eksik (chunked) olabilir: okuma da ayni sinirla kesilir.
    byte[] body = request.getInputStream().readNBytes((int) maxBodyBytes + 1);
    if (body.length > maxBodyBytes) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }

    Outcome outcome =
        ingestionService.ingest(integrationId, githubEvent, deliveryId, signature, body);
    return ResponseEntity.status(toStatus(outcome)).build();
  }

  private static HttpStatus toStatus(Outcome outcome) {
    return switch (outcome) {
      case ACCEPTED, IGNORED -> HttpStatus.ACCEPTED;
      case PONG -> HttpStatus.OK;
      case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
      case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
      case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
    };
  }
}
