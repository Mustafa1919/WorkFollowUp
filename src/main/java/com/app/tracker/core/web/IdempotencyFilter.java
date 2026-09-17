package com.app.tracker.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * PHASE_1_DETAILED_DESIGN.md Bolum 4.1 — kaynak yaratan (POST) endpoint'ler icin opsiyonel {@code
 * Idempotency-Key} header'i. Doc "DB tablosu veya Faz5 sonrasi Redis" diyor; Redis zaten bu projede
 * kurulu oldugu icin (Faz0) dogrudan Redis'te 24 saatlik TTL ile tutuluyor — ayri bir DB tablosu +
 * sonradan Redis'e tasima adimini atlar.
 *
 * <p>Anahtarin varligini {@code check-then-act} ile kontrol etmek (once GET, sonra SET) iki essiz
 * istek arasinda race yaratirdi: her ikisi de "anahtar yok" gorup islemi TEKRAR calistirir. Bunun
 * yerine Redis'in atomik {@code SETNX} karsiligi ({@code setIfAbsent}) ile anahtar once bir
 * "IN_PROGRESS" isaretiyle ELE GECIRILIR; ikinci istek bu isareti gorup 409 doner.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

  private static final Duration TTL = Duration.ofHours(24);
  private static final String HEADER = "Idempotency-Key";
  private static final String KEY_PREFIX = "idempotency:";
  private static final String IN_PROGRESS_MARKER = "IN_PROGRESS";

  private final StringRedisTemplate redisTemplate;

  public IdempotencyFilter(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    if (!"POST".equalsIgnoreCase(request.getMethod()) || key == null || key.isBlank()) {
      filterChain.doFilter(request, response);
      return;
    }

    String redisKey = KEY_PREFIX + key;
    Boolean claimed = redisTemplate.opsForValue().setIfAbsent(redisKey, IN_PROGRESS_MARKER, TTL);
    if (!Boolean.TRUE.equals(claimed)) {
      String existing = redisTemplate.opsForValue().get(redisKey);
      if (IN_PROGRESS_MARKER.equals(existing)) {
        response.sendError(
            HttpServletResponse.SC_CONFLICT, "Ayni Idempotency-Key ile bir istek zaten isleniyor.");
      } else {
        replayCachedResponse(response, existing);
      }
      return;
    }

    ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
    filterChain.doFilter(request, wrappedResponse);

    if (wrappedResponse.getStatus() < 400) {
      String body = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
      redisTemplate.opsForValue().set(redisKey, wrappedResponse.getStatus() + "\n" + body, TTL);
    } else {
      // Basarisiz istek: anahtari serbest birak, ayni Idempotency-Key ile tekrar denenebilsin.
      redisTemplate.delete(redisKey);
    }
    wrappedResponse.copyBodyToResponse();
  }

  private static void replayCachedResponse(HttpServletResponse response, String cached)
      throws IOException {
    int separatorIndex = cached.indexOf('\n');
    int status = Integer.parseInt(cached.substring(0, separatorIndex));
    String body = cached.substring(separatorIndex + 1);
    response.setStatus(status);
    response.setContentType("application/json");
    response.getWriter().write(body);
  }
}
