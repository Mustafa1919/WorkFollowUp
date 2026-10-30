package com.app.tracker.security.support;

import com.app.tracker.security.support.SecurityFixture.Actor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guvenlik testleri icin gercek servlet'e (RANDOM_PORT) giden ince HTTP yardimcisi. MockMvc
 * degildir: filtrelerin /error dispatch'i ve 401/403 farki yalniz gercek sunucuda gorunur.
 *
 * <p>{@code bearer} bos (null) ise {@code Authorization} gonderilmez; {@code workspaceId} null ise
 * {@code X-Workspace-Id} gonderilmez; {@code jsonBody} varsa {@code Content-Type: application/json}
 * eklenir. Bearer olarak {@code null} gecerken {@code (String) null} cast'i gerekir (Actor/String
 * asiri yuklemesi belirsiz kalmasin diye).
 */
public final class SecurityHttp {

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private final int port;

  public SecurityHttp(int port) {
    this.port = port;
  }

  public String url(String path) {
    return "http://localhost:" + port + path;
  }

  // ---- Actor ile --------------------------------------------------------------------------

  public HttpResponse<String> get(String path, Actor actor, UUID workspaceId, String jsonBody) {
    return send("GET", path, actor.accessToken(), workspaceId, jsonBody, Map.of());
  }

  public HttpResponse<String> post(String path, Actor actor, UUID workspaceId, String jsonBody) {
    return send("POST", path, actor.accessToken(), workspaceId, jsonBody, Map.of());
  }

  public HttpResponse<String> put(String path, Actor actor, UUID workspaceId, String jsonBody) {
    return send("PUT", path, actor.accessToken(), workspaceId, jsonBody, Map.of());
  }

  public HttpResponse<String> patch(String path, Actor actor, UUID workspaceId, String jsonBody) {
    return send("PATCH", path, actor.accessToken(), workspaceId, jsonBody, Map.of());
  }

  public HttpResponse<String> delete(String path, Actor actor, UUID workspaceId, String jsonBody) {
    return send("DELETE", path, actor.accessToken(), workspaceId, jsonBody, Map.of());
  }

  // ---- Ham bearer (JWT / PAT / bozuk deger) ile -------------------------------------------

  public HttpResponse<String> get(String path, String bearer, UUID workspaceId, String jsonBody) {
    return send("GET", path, bearer, workspaceId, jsonBody, Map.of());
  }

  public HttpResponse<String> post(String path, String bearer, UUID workspaceId, String jsonBody) {
    return send("POST", path, bearer, workspaceId, jsonBody, Map.of());
  }

  public HttpResponse<String> put(String path, String bearer, UUID workspaceId, String jsonBody) {
    return send("PUT", path, bearer, workspaceId, jsonBody, Map.of());
  }

  public HttpResponse<String> patch(String path, String bearer, UUID workspaceId, String jsonBody) {
    return send("PATCH", path, bearer, workspaceId, jsonBody, Map.of());
  }

  public HttpResponse<String> delete(
      String path, String bearer, UUID workspaceId, String jsonBody) {
    return send("DELETE", path, bearer, workspaceId, jsonBody, Map.of());
  }

  /**
   * D4: basliklari sirali liste olarak alan gonderim; ayni ad birden cok kez verilebilir (ornegin
   * iki {@code X-Workspace-Id}). {@code X-Workspace-Id} burada OTOMATIK eklenmez.
   */
  public HttpResponse<String> sendWithHeaders(
      String method,
      String path,
      String bearer,
      List<Map.Entry<String, String>> headers,
      String jsonBody) {
    BodyPublisher publisher =
        jsonBody == null ? BodyPublishers.noBody() : BodyPublishers.ofString(jsonBody);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url(path)))
            .timeout(Duration.ofSeconds(60))
            .method(method, publisher);
    if (jsonBody != null) {
      request.header("Content-Type", "application/json");
    }
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    headers.forEach(h -> request.header(h.getKey(), h.getValue()));
    try {
      return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /**
   * Genel gonderim; ek basliklar (ornegin {@code Cookie}, {@code Origin}) {@code extraHeaders} ile
   * verilir. {@code bearer} oldugu gibi {@code "Bearer " + bearer} olarak eklenir.
   */
  public HttpResponse<String> send(
      String method,
      String path,
      String bearer,
      UUID workspaceId,
      String jsonBody,
      Map<String, String> extraHeaders) {
    BodyPublisher publisher =
        jsonBody == null ? BodyPublishers.noBody() : BodyPublishers.ofString(jsonBody);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url(path)))
            .timeout(Duration.ofSeconds(60))
            .method(method, publisher);
    if (jsonBody != null) {
      request.header("Content-Type", "application/json");
    }
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    if (workspaceId != null) {
      request.header("X-Workspace-Id", workspaceId.toString());
    }
    extraHeaders.forEach(request::header);
    try {
      return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
