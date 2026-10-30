package com.app.tracker.security.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * D7: Mailpit'te alici bazli sayim ve davet token'i okuma. {@code urlOf}: test sinifindan {@code p
 * -> mailpitApiUrl(p)}. Alici filtresi Mailpit arama API'siyle ({@code to:}) yapilir, sonuc ayrica
 * yerelde alici adresi ve konuya gore suzulur (paylasilan Mailpit'te baska testlerin postalari
 * sayima karismasin).
 */
public final class MailpitInbox {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final Pattern INVITE_LINK = Pattern.compile("/invitations/([A-Za-z0-9_-]+)");

  private final Function<String, String> urlOf;
  private final ObjectMapper mapper;

  public MailpitInbox(Function<String, String> urlOf, ObjectMapper mapper) {
    this.urlOf = urlOf;
    this.mapper = mapper;
  }

  /** Aliciya giden, konusu {@code subjectPart} iceren posta sayisi (su anki durum). */
  public int count(String email, String subjectPart) {
    return messages(email, subjectPart).size();
  }

  /** Davet postasindan ham token'i okur (en fazla 30 sn bekler). */
  public String inviteToken(String email) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      for (JsonNode msg : messages(email, "davet edildin")) {
        String text =
            mapper
                .readTree(get("/api/v1/message/" + msg.path("ID").asString("")))
                .path("Text")
                .asString("");
        Matcher m = INVITE_LINK.matcher(text);
        if (m.find()) {
          return m.group(1);
        }
      }
      sleep(250);
    }
    throw new AssertionError("Davet postasi bulunamadi: " + email);
  }

  private List<JsonNode> messages(String email, String subjectPart) {
    String query = URLEncoder.encode("to:" + email, StandardCharsets.UTF_8);
    JsonNode root = mapper.readTree(get("/api/v1/search?limit=500&query=" + query));
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode msg : root.path("messages")) {
      boolean toMatches = false;
      for (JsonNode to : msg.path("To")) {
        toMatches |= email.equalsIgnoreCase(to.path("Address").asString(""));
      }
      if (toMatches && msg.path("Subject").asString("").contains(subjectPart)) {
        out.add(msg);
      }
    }
    return out;
  }

  private String get(String path) {
    try {
      return HTTP.send(
              HttpRequest.newBuilder(URI.create(urlOf.apply(path))).GET().build(),
              HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
