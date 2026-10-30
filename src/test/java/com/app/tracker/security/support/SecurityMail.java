package com.app.tracker.security.support;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Mailpit'ten dogrulama/sifirlama token'ini okur. {@code urlOf}: {@code
 * AbstractIntegrationTest.mailpitApiUrl} (protected) icin test sinifindan {@code p ->
 * mailpitApiUrl(p)} verilir. Her senaryo ayri e-posta kullanmalidir (en yeni degil ilk eslesen
 * posta okunur).
 */
public final class SecurityMail {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private final Function<String, String> urlOf;
  private final ObjectMapper mapper;

  public SecurityMail(Function<String, String> urlOf, ObjectMapper mapper) {
    this.urlOf = urlOf;
    this.mapper = mapper;
  }

  /** Konusu {@code subjectPart} iceren ilk postadan {@code token=} degerini dondurur (bekler). */
  public String rawToken(String email, String subjectPart) {
    try {
      long deadline = System.currentTimeMillis() + 30_000;
      while (System.currentTimeMillis() < deadline) {
        JsonNode list = mapper.readTree(get("/api/v1/messages?limit=200"));
        for (JsonNode msg : list.path("messages")) {
          boolean toMatches = false;
          for (JsonNode to : msg.path("To")) {
            toMatches |= email.equalsIgnoreCase(to.path("Address").asString(""));
          }
          if (!toMatches || !msg.path("Subject").asString("").contains(subjectPart)) {
            continue;
          }
          String text =
              mapper
                  .readTree(get("/api/v1/message/" + msg.path("ID").asString("")))
                  .path("Text")
                  .asString("");
          Matcher m = Pattern.compile("token=([^\\s\"<&]+)").matcher(text);
          if (m.find()) {
            return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
          }
        }
        Thread.sleep(250);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    throw new AssertionError("Mailpit'te '" + subjectPart + "' e-postasi bulunamadi: " + email);
  }

  private String get(String path) {
    try {
      return HTTP.send(
              HttpRequest.newBuilder(URI.create(urlOf.apply(path))).GET().build(),
              HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
