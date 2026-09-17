package com.app.tracker.core.security;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.security.dto.LoginRequest;
import com.app.tracker.core.security.dto.LoginResponse;
import com.app.tracker.core.security.dto.PasswordResetConfirmDto;
import com.app.tracker.core.security.dto.PasswordResetRequestDto;
import com.app.tracker.core.security.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.1/1.1.1/1.1.2/1.4 — auth endpoint'leri. Refresh token
 * yalniz {@code HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth} cookie'de tasinir, hicbir
 * zaman response body'sine konmaz.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private static final String REFRESH_COOKIE_NAME = "refresh_token";
  private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

  private final AuthService authService;
  private final CorsProperties corsProperties;

  public AuthController(AuthService authService, CorsProperties corsProperties) {
    this.authService = authService;
    this.corsProperties = corsProperties;
  }

  @PostMapping("/register")
  public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
    authService.register(request.email(), request.password(), request.fullName());
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
    AuthService.LoginResult result =
        authService.login(request.email(), request.password(), clientIp(httpRequest));
    return withRefreshCookie(result);
  }

  @PostMapping("/refresh")
  public ResponseEntity<LoginResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshTokenCookie,
      HttpServletRequest httpRequest) {
    verifyOriginForCookieEndpoints(httpRequest);
    if (refreshTokenCookie == null) {
      throw new BusinessRuleException("Refresh token bulunamadi.");
    }
    return withRefreshCookie(authService.refresh(refreshTokenCookie));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshTokenCookie,
      HttpServletRequest httpRequest) {
    verifyOriginForCookieEndpoints(httpRequest);
    if (refreshTokenCookie != null) {
      authService.logout(refreshTokenCookie);
    }
    ResponseCookie expiredCookie =
        ResponseCookie.from(REFRESH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(0)
            .build();
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
        .build();
  }

  @PostMapping("/password-reset/request")
  public ResponseEntity<Void> requestPasswordReset(
      @Valid @RequestBody PasswordResetRequestDto request) {
    authService.requestPasswordReset(request.email());
    // Enumeration korumasi: e-posta kayitli olsun ya da olmasin AYNI yanit.
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/password-reset/confirm")
  public ResponseEntity<Void> confirmPasswordReset(
      @Valid @RequestBody PasswordResetConfirmDto request) {
    authService.confirmPasswordReset(request.token(), request.newPassword());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/verify-email")
  public ResponseEntity<Void> verifyEmail(@RequestParam String token) {
    authService.verifyEmail(token);
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<LoginResponse> withRefreshCookie(AuthService.LoginResult result) {
    ResponseCookie cookie =
        ResponseCookie.from(REFRESH_COOKIE_NAME, result.refreshToken())
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(result.refreshTtl())
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(new LoginResponse(result.accessToken(), result.refreshTtl().toSeconds()));
  }

  /**
   * Bolum 1.1.2 "Derinlemesine Savunma": SameSite'i desteklemeyen istemcilere karsi ek Origin
   * dogrulamasi. Origin header'i yoksa (eski/native istemci) reddetmiyoruz — sadece VARSA ve izinli
   * listede degilse reddediyoruz.
   */
  private void verifyOriginForCookieEndpoints(HttpServletRequest request) {
    String origin = request.getHeader(HttpHeaders.ORIGIN);
    if (origin != null
        && !corsProperties.getAllowedOrigins().isEmpty()
        && !corsProperties.getAllowedOrigins().contains(origin)) {
      throw new BusinessRuleException("Gecersiz istek kaynagi.");
    }
  }

  private static String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
  }
}
