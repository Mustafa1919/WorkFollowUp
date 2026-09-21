package com.app.tracker.core.security;

import com.app.tracker.core.tenancy.WorkspaceContextFilter;
import com.app.tracker.core.web.IdempotencyFilter;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.1.2 — Stateless JWT API. CSRF Spring'in klasik
 * session-tabanli mekanizmasiyla degil, {@code Authorization: Bearer} header'inin dogasi
 * (cross-site formlarla gonderilemez) + refresh/logout icin ozel Origin kontrolu (bkz.
 * AuthController) ile kapatilir; bu yuzden Spring'in yerlesik CSRF filtresi devre disi.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final WorkspaceContextFilter workspaceContextFilter;
  private final IdempotencyFilter idempotencyFilter;
  private final CorsProperties corsProperties;

  public SecurityConfig(
      JwtAuthenticationFilter jwtAuthenticationFilter,
      WorkspaceContextFilter workspaceContextFilter,
      IdempotencyFilter idempotencyFilter,
      CorsProperties corsProperties) {
    this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    this.workspaceContextFilter = workspaceContextFilter;
    this.idempotencyFilter = idempotencyFilter;
    this.corsProperties = corsProperties;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                // /api/v1/webhooks/**: dis sistemler (GitHub) JWT tasimaz; kimlik dogrulamasi
                // controller'daki HMAC imza kontroludur (bkz. GithubWebhookController).
                auth.requestMatchers(
                        "/api/v1/auth/**",
                        "/api/v1/webhooks/**",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/ws/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(workspaceContextFilter, JwtAuthenticationFilter.class)
        .addFilterAfter(idempotencyFilter, WorkspaceContextFilter.class);
    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(
        List.of("Authorization", "Content-Type", "X-Workspace-Id", "Idempotency-Key"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(Duration.ofHours(1));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
