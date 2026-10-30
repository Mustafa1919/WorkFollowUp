package com.app.tracker.core.ratelimit;

import com.app.tracker.core.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Uc bazli hiz siniri (SEC-07: DOS-01, DOS-07). Kural tablosu {@link RateLimitRule#DEFAULTS};
 * tabloda olmayan istekler bu filtreden etkilenmez (Redis'e gidilmez).
 *
 * <p>Guvenlik zincirinde PatAuthenticationFilter'dan SONRA, WorkspaceContextFilter'dan ONCE
 * calisir: kimlik bilinir, ama uyelik sorgusu ve idempotency kilidi olusmadan reddedilir. Kullanici
 * kuralinda kimlik yoksa kural uygulanmaz (o uclar zaten 401 doner).
 *
 * <p>Yol, Spring MVC ile ayni bicimde cozulur (URL-decode, ';' icerigi atilir) ki kodlanmis yol
 * sinirdan kacamasin. 429 yaniti {@code sendError} ile DEGIL dogrudan yazilir.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final String KEY_PREFIX = "rate:";

  private final RateLimiter rateLimiter;
  private final ClientIpResolver clientIpResolver;
  private final boolean enabled;
  private final List<RateLimitRule> rules;
  private final UrlPathHelper pathHelper = new UrlPathHelper();

  public RateLimitFilter(
      RateLimiter rateLimiter, ClientIpResolver clientIpResolver, RateLimitProperties properties) {
    this.rateLimiter = rateLimiter;
    this.clientIpResolver = clientIpResolver;
    this.enabled = properties.isEnabled();
    List<RateLimitRule> effective = new ArrayList<>();
    for (RateLimitRule rule : RateLimitRule.DEFAULTS) {
      effective.add(rule.withOverride(properties.getRules().get(rule.name())));
    }
    this.rules = List.copyOf(effective);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    RateLimitRule rule = enabled ? match(request) : null;
    if (rule == null) {
      filterChain.doFilter(request, response);
      return;
    }
    String subject = subject(rule, request);
    if (subject == null) {
      filterChain.doFilter(request, response);
      return;
    }
    String key = KEY_PREFIX + rule.name() + ":" + rule.keyType().keyPart() + ":" + subject;
    RateLimiter.Decision decision = rateLimiter.hit(key, rule.limit(), rule.window());
    if (!decision.allowed()) {
      writeTooManyRequests(response, decision.retryAfterSeconds());
      return;
    }
    filterChain.doFilter(request, response);
  }

  RateLimitRule match(HttpServletRequest request) {
    String method = request.getMethod();
    String path = pathHelper.getPathWithinApplication(request);
    for (RateLimitRule rule : rules) {
      if (rule.method().equals(method) && rule.path().equals(path)) {
        return rule;
      }
    }
    return null;
  }

  private String subject(RateLimitRule rule, HttpServletRequest request) {
    if (rule.keyType() == RateLimitRule.KeyType.IP) {
      return clientIpResolver.resolve(request);
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UUID userId) {
      return userId.toString();
    }
    return null;
  }

  private static void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds)
      throws IOException {
    response.setStatus(429);
    response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"type\":\"https://api.app.com/errors/rate-limited\",\"title\":\"Cok Fazla Istek\","
                + "\"status\":429,\"detail\":\"Istek siniri asildi, lutfen daha sonra tekrar"
                + " deneyin.\"}");
  }
}
