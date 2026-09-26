package com.app.tracker.core.security;

import com.app.tracker.accesstoken.service.AuthenticatedPat;
import com.app.tracker.accesstoken.service.PersonalAccessTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR-0018 -- {@code wf_pat_} onekli Bearer token'lar icin JwtAuthenticationFilter'in PAT
 * karsiligi. Onek eslesmiyorsa hicbir sey yapmaz (JWT zaten JwtAuthenticationFilter'da islendi).
 * Basarili kimlik dogrulamadan SONRA rate limit uygulanir (gecersiz token'lar icin butce harcanmaz
 * -- token uzayi 256-bit oldugu icin brute-force riski yok, BruteForceGuard'daki parola
 * senaryosuyla KARISTIRILMAMALI). Asilan limit "USER"/"SYSTEM_ADMIN" yerine "AUTH_PAT" marker
 * yetkisi tasir -- bu, PAT'in kendi kendini yonetememesi (token olusturma/iptal) icin
 * AccessTokenController'daki {@code @PreAuthorize} kontrolunun temelidir (Karar 3).
 */
@Component
public class PatAuthenticationFilter extends OncePerRequestFilter {

  private final PersonalAccessTokenService personalAccessTokenService;
  private final PatRateLimiter rateLimiter;

  public PatAuthenticationFilter(
      PersonalAccessTokenService personalAccessTokenService, PatRateLimiter rateLimiter) {
    this.personalAccessTokenService = personalAccessTokenService;
    this.rateLimiter = rateLimiter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring("Bearer ".length());
      if (PatTokenFormat.matches(token)) {
        Optional<AuthenticatedPat> authenticated = personalAccessTokenService.authenticate(token);
        if (authenticated.isEmpty()) {
          SecurityContextHolder.clearContext();
          filterChain.doFilter(request, response);
          return;
        }
        PatRateLimiter.CheckResult check =
            rateLimiter.checkAndIncrement(authenticated.get().tokenId());
        if (!check.allowed()) {
          writeTooManyRequests(response, check.retryAfterSeconds());
          return;
        }
        List<GrantedAuthority> authorities =
            List.of(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("AUTH_PAT"));
        var authentication =
            new UsernamePasswordAuthenticationToken(
                authenticated.get().userId(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    }
    filterChain.doFilter(request, response);
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
                + "\"status\":429,\"detail\":\"Bu token icin istek siniri asildi.\"}");
  }
}
