package com.app.tracker.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sorgu parametresi korumasi (SEC-20). Yalnizca sorgu dizesine bakar; govde parametrelerine
 * dokunmaz (getParameterMap govdeyi tuketirdi).
 *
 * <ul>
 *   <li>Ad ya da deger U+0000 iceriyorsa 400 (problem+json, dogrudan yazilir; sendError {@code
 *       /error} dispatch'i ile 401'e donerdi). NUL kontrolu bos adin yok sayilmasindan ONCE
 *       yapilir.
 *   <li>Adi bos parametre ({@code ?=}, {@code ?=x}) Tomcat'te {@code InvalidParameterException}
 *       atar (Parameters.processParameters, ilk parametre erisiminde); bu durumda istek, bos adli
 *       parcalar suzulmus sorgu dizesinden turetilen parametrelerle sarilir. Normal isteklerde
 *       sarmalayici kullanilmaz.
 * </ul>
 *
 * Sira: {@code HIGHEST_PRECEDENCE + 5}, guvenlik zincirinin ve govde sinirinin onunde.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class QueryParameterGuardFilter extends OncePerRequestFilter {

  private static final String BAD_REQUEST_BODY =
      "{\"type\":\"https://api.app.com/errors/bad-request\",\"title\":\"Gecersiz Istek\","
          + "\"status\":400,\"detail\":\"Sorgu parametresi gecersiz karakter iceriyor.\"}";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri == null || !uri.startsWith("/api/") || request.getQueryString() == null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    boolean emptyName = false;
    Map<String, List<String>> kept = new LinkedHashMap<>();
    for (String chunk : request.getQueryString().split("&")) {
      if (chunk.isEmpty()) {
        continue;
      }
      int eq = chunk.indexOf('=');
      String rawName = eq < 0 ? chunk : chunk.substring(0, eq);
      String rawValue = eq < 0 ? "" : chunk.substring(eq + 1);
      String name = decode(rawName);
      String value = decode(rawValue);
      if (name.indexOf('\0') >= 0 || value.indexOf('\0') >= 0) {
        writeBadRequest(response);
        return;
      }
      if (rawName.isEmpty()) {
        emptyName = true;
        continue;
      }
      kept.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }
    if (!emptyName) {
      filterChain.doFilter(request, response);
      return;
    }
    if (isFormBody(request.getContentType())) {
      writeBadRequest(response);
      return;
    }
    filterChain.doFilter(new FilteredParameterRequest(request, kept), response);
  }

  /**
   * Govdeyi okumadan Content-Type'a bakar; form-urlencoded ve multipart/* gövde parametresi tasir.
   */
  private static boolean isFormBody(String contentType) {
    if (contentType == null) {
      return false;
    }
    String ct = contentType.toLowerCase(java.util.Locale.ROOT).trim();
    return ct.startsWith("application/x-www-form-urlencoded") || ct.startsWith("multipart/");
  }

  private static String decode(String raw) {
    try {
      return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return raw;
    }
  }

  private static void writeBadRequest(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BAD_REQUEST_BODY);
  }

  /** Bos adli parcalari atmis sorgu parametreleri; dort okuma yontemi tutarlidir. */
  private static final class FilteredParameterRequest extends HttpServletRequestWrapper {

    private final Map<String, String[]> parameters = new LinkedHashMap<>();

    FilteredParameterRequest(HttpServletRequest request, Map<String, List<String>> kept) {
      super(request);
      kept.forEach((k, v) -> parameters.put(k, v.toArray(new String[0])));
    }

    @Override
    public String getParameter(String name) {
      String[] values = parameters.get(name);
      return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
      Map<String, String[]> copy = new LinkedHashMap<>();
      parameters.forEach((k, v) -> copy.put(k, v.clone()));
      return Collections.unmodifiableMap(copy);
    }

    @Override
    public Enumeration<String> getParameterNames() {
      return Collections.enumeration(parameters.keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
      String[] values = parameters.get(name);
      return values == null ? null : values.clone();
    }
  }
}
