package com.app.tracker.core.web;

import com.app.tracker.core.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 4.1 — kaynak yaratan (POST) endpoint'ler icin opsiyonel {@code
 * Idempotency-Key} header'i; Redis'te 24 saatlik TTL ile tutulur.
 *
 * <p>Onbellek, istemcinin gonderdigi ham anahtara DEGIL, istegin kimligine baglidir: Redis anahtari
 * {@code idempotency:} + SHA-256(userId, workspaceId, metot, URI+query, istemci anahtari). Boylece
 * baska kullanici/kiraci/uc ayni anahtari gonderse bile ayri girdi olusur. Ek olarak govde parmak
 * izi (SHA-256) saklanir; ayni anahtar farkli govdeyle gelirse 422 doner. Filtre yalniz kimligi
 * dogrulanmis (principal {@code UUID}) kullanicilar icin calisir; anonim istekte hicbir sey yapmaz.
 *
 * <p>Anahtarin varligini check-then-act ile kontrol etmek iki essiz istek arasinda race yaratirdi.
 * Bunun yerine atomik {@code SETNX} ({@code setIfAbsent}) ile anahtar once {@code IN_PROGRESS}
 * isaretiyle ele gecirilir; ikinci istek 409 alir.
 *
 * <p>Istisna listesi ({@link #EXCLUDED_PREFIXES}): yanit govdesinde sir tasiyan (access token, duz
 * metin PAT) uclar. Bunlarin yaniti 24 saat Redis'te tutulmamali ve tekrar oynatilmamalidir.
 *
 * <p>{@code multipart/*} ve {@code application/x-www-form-urlencoded} istekleri filtre disidir:
 * govdeyi onceden okumak Tomcat'in getParameter/getParts ayristirmasini bozar. Yukleme ucu
 * eklenirse idempotency ayrica tasarlanmalidir.
 *
 * <p>Hata yanitlari {@code sendError} ile DEGIL dogrudan yazilir: sendError container'da {@code
 * /error} dispatch'i baslatir ve yanit 401'e donerdi (bkz. WorkspaceContextFilter).
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

  static final int MAX_BODY_BYTES = 1024 * 1024;
  private static final int MAX_KEY_LENGTH = 255;
  private static final Duration TTL = Duration.ofHours(24);
  private static final String HEADER = "Idempotency-Key";
  private static final String KEY_PREFIX = "idempotency:";
  private static final String IN_PROGRESS_MARKER = "IN_PROGRESS";

  /** Yanit govdesinde sir (token) tasiyan uclar; onbellege alinmaz. */
  private static final List<String> EXCLUDED_PREFIXES =
      List.of("/api/v1/auth/", "/api/v1/access-tokens");

  private final StringRedisTemplate redisTemplate;

  public IdempotencyFilter(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!"POST".equalsIgnoreCase(request.getMethod())
        || key == null
        || key.isBlank()
        || authentication == null
        || !(authentication.getPrincipal() instanceof UUID userId)
        || isExcluded(request.getRequestURI())
        || isFormContent(request.getContentType())) {
      filterChain.doFilter(request, response);
      return;
    }

    if (key.length() > MAX_KEY_LENGTH) {
      writeProblem(
          response,
          HttpServletResponse.SC_BAD_REQUEST,
          "bad-request",
          "Gecersiz Istek",
          "Idempotency-Key en fazla 255 karakter olabilir.");
      return;
    }
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      writeTooLarge(response);
      return;
    }
    byte[] body;
    try {
      body = readBody(request);
    } catch (RequestBodyTooLargeException e) {
      // Genel sinir (RequestBodyLimitFilter) asildi; ayni problem govdesi, Redis'e dokunulmadi.
      RequestBodyLimitFilter.writeTooLarge(response, e);
      return;
    }
    if (body == null) {
      writeTooLarge(response);
      return;
    }

    String fingerprint = sha256Hex(body);
    String redisKey = KEY_PREFIX + sha256Hex(scope(request, userId, key).getBytes(UTF_8));
    Boolean claimed =
        redisTemplate
            .opsForValue()
            .setIfAbsent(redisKey, IN_PROGRESS_MARKER + "\n" + fingerprint, TTL);
    if (!Boolean.TRUE.equals(claimed)) {
      replayOrReject(response, redisTemplate.opsForValue().get(redisKey), fingerprint);
      return;
    }

    HttpServletRequest replayable = new CachedBodyRequest(request, body);
    ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
    boolean completed = false;
    try {
      filterChain.doFilter(replayable, wrappedResponse);
      completed = true;
    } finally {
      if (completed && wrappedResponse.getStatus() < 400) {
        String responseBody =
            new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
        redisTemplate
            .opsForValue()
            .set(
                redisKey,
                wrappedResponse.getStatus() + "\n" + fingerprint + "\n" + responseBody,
                TTL);
      } else {
        // Basarisiz istek: anahtari serbest birak, ayni Idempotency-Key ile tekrar denenebilsin.
        redisTemplate.delete(redisKey);
      }
    }
    wrappedResponse.copyBodyToResponse();
  }

  private static final Charset UTF_8 = StandardCharsets.UTF_8;

  private static boolean isFormContent(String contentType) {
    if (contentType == null) {
      return false;
    }
    String type = contentType.toLowerCase(java.util.Locale.ROOT).trim();
    return type.startsWith("multipart/") || type.startsWith("application/x-www-form-urlencoded");
  }

  private static boolean isExcluded(String uri) {
    for (String prefix : EXCLUDED_PREFIXES) {
      if (uri.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static String scope(HttpServletRequest request, UUID userId, String key) {
    UUID workspaceId = TenantContext.getWorkspaceId();
    String query = request.getQueryString();
    return userId
        + "\n"
        + (workspaceId == null ? "-" : workspaceId.toString())
        + "\n"
        + request.getMethod().toUpperCase(java.util.Locale.ROOT)
        + "\n"
        + request.getRequestURI()
        + (query == null ? "" : "?" + query)
        + "\n"
        + key;
  }

  /** En fazla {@link #MAX_BODY_BYTES} bayt okur; asilirsa {@code null} doner. */
  private static byte[] readBody(HttpServletRequest request) throws IOException {
    try (InputStream in = request.getInputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int total = 0;
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > MAX_BODY_BYTES) {
          return null;
        }
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  private static void replayOrReject(
      HttpServletResponse response, String existing, String fingerprint) throws IOException {
    if (existing == null) {
      writeConflict(response);
      return;
    }
    String[] parts = existing.split("\n", 3);
    if (parts.length < 2 || !parts[1].equals(fingerprint)) {
      writeProblem(
          response,
          422,
          "idempotency-key-reuse",
          "Islenemeyen Istek",
          "Ayni Idempotency-Key farkli bir istek govdesiyle kullanildi.");
      return;
    }
    if (IN_PROGRESS_MARKER.equals(parts[0]) || parts.length < 3) {
      writeConflict(response);
      return;
    }
    response.setStatus(Integer.parseInt(parts[0]));
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(parts[2]);
  }

  private static void writeConflict(HttpServletResponse response) throws IOException {
    writeProblem(
        response,
        HttpServletResponse.SC_CONFLICT,
        "conflict",
        "Cakisma",
        "Ayni Idempotency-Key ile bir istek zaten isleniyor.");
  }

  private static void writeTooLarge(HttpServletResponse response) throws IOException {
    writeProblem(
        response,
        413,
        "payload-too-large",
        "Istek Cok Buyuk",
        "Idempotency-Key kullanilan isteklerde govde en fazla 1 MiB olabilir.");
  }

  private static void writeProblem(
      HttpServletResponse response, int status, String type, String title, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"type\":\"https://api.app.com/errors/"
                + type
                + "\",\"title\":\""
                + title
                + "\",\"status\":"
                + status
                + ",\"detail\":\""
                + detail
                + "\"}");
  }

  private static String sha256Hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Govdesi onceden okunmus istek: {@code getInputStream()} ve {@code getReader()} tekrar okur. */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream source = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return source.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return source.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return source.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }
}
