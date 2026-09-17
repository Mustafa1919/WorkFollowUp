package com.app.tracker.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.1 + "JWT Iptali icin Redis Blacklist" ipucu. {@code
 * Authorization: Bearer} header'ini dogrular; jti Redis kara listesindeyse (logout veya
 * reuse-detection sonrasi aile iptali) istek reddedilir. Token yoksa veya gecersizse
 * SecurityContext bos birakilir — asagidaki authorizeHttpRequests kurali 401/403'e cevirir.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final StringRedisTemplate redisTemplate;

  public JwtAuthenticationFilter(JwtService jwtService, StringRedisTemplate redisTemplate) {
    this.jwtService = jwtService;
    this.redisTemplate = redisTemplate;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring("Bearer ".length());
      try {
        DecodedJwt decoded = jwtService.verify(token);
        if (Boolean.TRUE.equals(redisTemplate.hasKey("jwt:blacklist:" + decoded.jti()))) {
          throw new JwtValidationException("Token iptal edilmis.");
        }
        List<GrantedAuthority> authorities =
            decoded.roles().stream()
                .map(SimpleGrantedAuthority::new)
                .map(a -> (GrantedAuthority) a)
                .toList();
        var authentication =
            new UsernamePasswordAuthenticationToken(decoded.userId(), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (JwtValidationException e) {
        SecurityContextHolder.clearContext();
      }
    }
    filterChain.doFilter(request, response);
  }
}
