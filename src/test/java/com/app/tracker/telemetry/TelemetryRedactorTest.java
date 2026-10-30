package com.app.tracker.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TelemetryRedactorTest {

  private static final String JWT =
      "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3OCJ9.c2lnbmF0dXJlLWRlbmVtZQ";

  private final TelemetryRedactor r = new TelemetryRedactor();

  @Test
  void emailIsMasked() {
    assertEquals(
        "user [email] failed", r.redactMessage("user ad.soyad+etiket@alt.example.com failed"));
  }

  @Test
  void plainMessageUnchanged() {
    String m = "Cannot read properties of undefined (reading 'map')";
    assertEquals(m, r.redactMessage(m));
  }

  @Test
  void patFullyMasked() {
    String out = r.redactMessage("401 for wf_pat_AbC123_-xyz on /r");
    assertEquals("401 for [pat] on /r", out);
  }

  @Test
  void jwtMaskedAlsoWithEmptySignature() {
    assertEquals("a [jwt] b", r.redactMessage("a " + JWT + " b"));
    assertEquals("a [jwt]", r.redactMessage("a eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0."));
  }

  @Test
  void bearerMaskedCaseInsensitive() {
    assertEquals("x Bearer [redacted] y", r.redactMessage("x bEaReR opaque-value_1 y"));
    assertEquals("Bearer [redacted] rejected", r.redactMessage("Bearer " + JWT + " rejected"));
  }

  @Test
  void bearerValueCoversAllNonWhitespace() {
    assertEquals("x Bearer [redacted] y", r.redactMessage("x Bearer opaque:private-value y"));
    assertEquals("/b/Bearer [redacted]", r.redactPath("/b/Bearer opaque:private-value"));
  }

  @Test
  void sensitiveQueryParamsMaskedOthersKept() {
    assertEquals(
        "GET /a?page=2&token=[redacted]&size=50&Access_Token=[redacted]",
        r.redactMessage("GET /a?page=2&token=abc123&size=50&Access_Token=zzz"));
    assertEquals("/x?page=2&size=50", r.redactMessage("/x?page=2&size=50"));
  }

  @Test
  void pathRules() {
    assertEquals("/invitations/{token}", r.redactPath("/invitations/abc123?x=1#h"));
    assertEquals("/api/v1/invitations/{token}", r.redactPath("/api/v1/invitations/abc"));
    assertEquals(
        "/api/v1/invitations/{token}/accept", r.redactPath("/api/v1/invitations/abc/accept"));
    String uuidPath = "/api/v1/projects/" + UUID.randomUUID() + "/tasks";
    assertEquals(uuidPath, r.redactPath(uuidPath));
    assertEquals("/reports", r.redactPath("/reports?token=abc"));
    assertEquals("/reports", r.redactPath("/reports#frag"));
    assertNull(r.redactPath(null));
  }

  @Test
  void pathIdentifiersMasked() {
    assertEquals("/u/[email]/x", r.redactPath("/u/a.b+c@alt.example.com/x"));
    assertEquals("/p/[pat]", r.redactPath("/p/wf_pat_AbC123_-xyz"));
    assertEquals("/j/[jwt]", r.redactPath("/j/" + JWT));
    assertEquals("/b/Bearer [redacted]", r.redactPath("/b/Bearer abc.def"));
    String uuidPath = "/api/v1/projects/" + UUID.randomUUID() + "/tasks";
    assertEquals(uuidPath, r.redactPath(uuidPath));
  }

  @Test
  void errorTypeOnlyIdentifiersMasked() {
    String t = "log05-email-" + UUID.randomUUID();
    assertEquals(t, r.redactErrorType(t));
    assertEquals("E [email]", r.redactErrorType("E a@b.com"));
    assertNull(r.redactErrorType(null));
  }

  @Test
  void nullMessageStaysNullAndLongMessageTruncated() {
    assertNull(r.redactMessage(null));
    String out = r.redactMessage("a".repeat(10_000));
    assertEquals(TelemetryRedactor.MAX_MESSAGE_LENGTH, out.length());
  }

  @Test
  void pathologicalInputIsFast() {
    long start = System.nanoTime();
    r.redactMessage("a.".repeat(2500) + "-".repeat(2000));
    assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);
  }

  @Test
  @SuppressWarnings("unchecked")
  void publisherSendsMaskedPayload() throws Exception {
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
    ObjectMapper mapper = JsonMapper.builder().build();
    TelemetryEventPublisher p = new TelemetryEventPublisher(kafka, mapper, r);

    p.publishError(
        null, null, "frontend", "T", "mail a@b.com", "/invitations/secret?x=1", Instant.now());

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(anyString(), anyString(), json.capture());
    JsonNode payload = mapper.readTree(json.getValue()).get("payload");
    assertEquals("mail [email]", payload.get("message").asString());
    assertEquals("/invitations/{token}", payload.get("path").asString());
    assertFalse(json.getValue().contains("secret"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void redactionFailureIsFailClosed() throws Exception {
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
    ObjectMapper mapper = JsonMapper.builder().build();
    TelemetryRedactor broken = mock(TelemetryRedactor.class);
    when(broken.redactMessage(anyString())).thenThrow(new IllegalStateException("boom"));
    TelemetryEventPublisher p = new TelemetryEventPublisher(kafka, mapper, broken);

    p.publishError(null, null, "backend", null, "raw a@b.com", null, Instant.now());

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(anyString(), anyString(), json.capture());
    JsonNode payload = mapper.readTree(json.getValue()).get("payload");
    assertEquals(TelemetryRedactor.FAILED, payload.get("message").asString());
    assertFalse(json.getValue().contains("a@b.com"));
  }
}
