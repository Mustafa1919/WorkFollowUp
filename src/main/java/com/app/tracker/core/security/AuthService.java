package com.app.tracker.core.security;

import com.app.tracker.core.audit.SecurityAuditEvent;
import com.app.tracker.core.audit.SecurityAuditLog;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.notification.EmailNotificationPublisher;
import com.app.tracker.core.ratelimit.RateLimiter;
import com.app.tracker.core.security.model.RefreshToken;
import com.app.tracker.core.security.model.VerificationToken;
import com.app.tracker.core.security.repository.RefreshTokenRepository;
import com.app.tracker.core.security.repository.VerificationTokenRepository;
import com.app.tracker.user.model.EmailAddresses;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.1/1.1.1/1.4 — login/refresh/logout/parola
 * sifirlama/e-posta dogrulama akislarinin tek orkestrasyon noktasi. Bu tablolarin hicbiri RLS'e
 * tabi degildir; TenantContext bu servis boyunca hep null kalir (guard aspect bunu sorun etmez,
 * sadece aktif transaction'i zorunlu kilar).
 */
@Service
public class AuthService {

  private static final Duration REUSE_GRACE_PERIOD = Duration.ofSeconds(30);
  private static final int MIN_PASSWORD_LENGTH = 12;

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final VerificationTokenRepository verificationTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final JwtProperties jwtProperties;
  private final BruteForceGuard bruteForceGuard;
  private final EmailNotificationPublisher emailNotificationPublisher;
  private final StringRedisTemplate redisTemplate;
  private final SystemAdminProperties systemAdminProperties;
  private final RefreshTokenFamilyRevoker familyRevoker;
  private final String dummyHash;
  private final Clock clock;
  private final PasswordResetMailBudget passwordResetMailBudget;
  private final SecurityAuditLog auditLog;

  public AuthService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      VerificationTokenRepository verificationTokenRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      JwtProperties jwtProperties,
      BruteForceGuard bruteForceGuard,
      EmailNotificationPublisher emailNotificationPublisher,
      StringRedisTemplate redisTemplate,
      SystemAdminProperties systemAdminProperties,
      RefreshTokenFamilyRevoker familyRevoker,
      Clock clock) {
    this(
        userRepository,
        refreshTokenRepository,
        verificationTokenRepository,
        passwordEncoder,
        jwtService,
        jwtProperties,
        bruteForceGuard,
        emailNotificationPublisher,
        redisTemplate,
        systemAdminProperties,
        familyRevoker,
        clock,
        new PasswordResetMailBudget(
            new RateLimiter(redisTemplate), new PasswordResetMailProperties()));
  }

  public AuthService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      VerificationTokenRepository verificationTokenRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      JwtProperties jwtProperties,
      BruteForceGuard bruteForceGuard,
      EmailNotificationPublisher emailNotificationPublisher,
      StringRedisTemplate redisTemplate,
      SystemAdminProperties systemAdminProperties,
      RefreshTokenFamilyRevoker familyRevoker,
      Clock clock,
      PasswordResetMailBudget passwordResetMailBudget) {
    this(
        userRepository,
        refreshTokenRepository,
        verificationTokenRepository,
        passwordEncoder,
        jwtService,
        jwtProperties,
        bruteForceGuard,
        emailNotificationPublisher,
        redisTemplate,
        systemAdminProperties,
        familyRevoker,
        clock,
        passwordResetMailBudget,
        new SecurityAuditLog());
  }

  /** Spring kurucusu: audit bean'i yoksa (dar test baglamlari) sessiz varsayilan kullanilir. */
  @Autowired
  public AuthService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      VerificationTokenRepository verificationTokenRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      JwtProperties jwtProperties,
      BruteForceGuard bruteForceGuard,
      EmailNotificationPublisher emailNotificationPublisher,
      StringRedisTemplate redisTemplate,
      SystemAdminProperties systemAdminProperties,
      RefreshTokenFamilyRevoker familyRevoker,
      Clock clock,
      PasswordResetMailBudget passwordResetMailBudget,
      @Nullable SecurityAuditLog auditLog) {
    this.auditLog = auditLog == null ? new SecurityAuditLog() : auditLog;
    this.passwordResetMailBudget = passwordResetMailBudget;
    this.clock = clock;
    this.familyRevoker = familyRevoker;
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.verificationTokenRepository = verificationTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.jwtProperties = jwtProperties;
    this.bruteForceGuard = bruteForceGuard;
    this.emailNotificationPublisher = emailNotificationPublisher;
    this.redisTemplate = redisTemplate;
    this.systemAdminProperties = systemAdminProperties;
    // Bolum 1.4.1 Timing Attack korumasi: kayitli olmayan e-posta icin de bcrypt bir hash'e
    // karsi calissin diye sabit bir "dummy" hash onceden hesaplaniyor.
    this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-protection");
  }

  public record LoginResult(String accessToken, String refreshToken, Duration refreshTtl) {}

  @Transactional
  public User register(
      String email, String rawPassword, String fullName, String privacyNoticeVersion) {
    if (!PrivacyNotice.CURRENT_VERSION.equals(privacyNoticeVersion)) {
      throw new BusinessRuleException(
          "Aydınlatma metni güncellendi; sayfayı yenileyip yeniden onaylayın.");
    }
    requireMinLength(rawPassword);
    String canonicalEmail = EmailAddresses.canonical(email);
    userRepository
        .findByEmail(canonicalEmail)
        .ifPresent(
            u -> {
              throw new BusinessRuleException("Bu e-posta zaten kayıtlı.");
            });
    User user =
        User.newUser(
            UUID.randomUUID(), canonicalEmail, passwordEncoder.encode(rawPassword), fullName);
    user.acceptPrivacyNotice(privacyNoticeVersion, Instant.now(clock));
    userRepository.save(user);
    issueVerificationToken(user.getId(), "EMAIL_VERIFICATION", Duration.ofHours(24));
    return user;
  }

  @Transactional
  public LoginResult login(String rawEmail, String rawPassword, String clientIp) {
    // SEC-14: sayaclar ve arama kanonik e-postaya baglidir (harf degistirerek kilit atlatilamaz).
    String email = EmailAddresses.canonical(rawEmail);
    BruteForceGuard.CheckResult check = bruteForceGuard.checkBeforeAttempt(email, clientIp);
    if (check.locked()) {
      auditLoginFailed(email, clientIp, "locked");
      throw new BusinessRuleException(
          "Hesap geçici olarak kilitlendi. Lütfen 15 dakika sonra tekrar deneyin.");
    }
    if (check.throttled()) {
      auditLoginFailed(email, clientIp, "throttled");
      throw new BusinessRuleException(
          "Çok fazla başarısız deneme. Lütfen biraz sonra tekrar deneyin.");
    }

    Optional<User> userOpt = userRepository.findByEmail(email);
    String hashToCheck = userOpt.map(User::getPasswordHash).orElse(dummyHash);
    boolean matches = passwordEncoder.matches(rawPassword, hashToCheck);

    if (userOpt.isEmpty() || !matches) {
      UUID knownUserId = userOpt.map(User::getId).orElse(null);
      if (knownUserId == null) {
        bruteForceGuard.recordFailure(email, clientIp);
      } else {
        bruteForceGuard.recordFailure(email, clientIp, knownUserId);
      }
      SecurityAuditLog.safeRecord(
          auditLog,
          SecurityAuditEvent.LOGIN_FAILED,
          knownUserId,
          Map.of("ip", String.valueOf(clientIp), "reason", "bad_credentials"));
      // Enumeration korumasi (Bolum 1.4.1): kullanici var/yok her durumda AYNI mesaj.
      throw new BusinessRuleException("E-posta veya şifre hatalı.");
    }

    bruteForceGuard.recordSuccess(email);
    return issueTokenPair(userOpt.get(), UUID.randomUUID());
  }

  @Transactional
  public LoginResult refresh(String rawRefreshToken) {
    RefreshToken token = requireValidRefreshToken(rawRefreshToken);

    if (token.getUsedAt() != null) {
      if (token.getUsedAt().plus(REUSE_GRACE_PERIOD).isAfter(Instant.now())) {
        return reissueWithinGracePeriod(token);
      }
      familyRevoker.revokeFamily(token.getFamilyId());
      SecurityAuditLog.safeRecord(
          auditLog,
          SecurityAuditEvent.REFRESH_TOKEN_REUSE,
          token.getUserId(),
          Map.of("family", String.valueOf(token.getFamilyId())));
      throw new RefreshTokenReuseException(
          "Güvenlik ihlali tespit edildi, tüm oturumlar sonlandırıldı. Lütfen yeniden giriş yapın.");
    }

    token.markUsed(Instant.now());
    refreshTokenRepository.save(token);

    User user = requireUser(token.getUserId());
    return issueTokenPair(user, token.getFamilyId());
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    String hash = TokenHasher.sha256Hex(rawRefreshToken);
    refreshTokenRepository
        .findByTokenHash(hash)
        .ifPresent(
            token -> {
              token.revoke();
              refreshTokenRepository.save(token);
              if (token.getAccessJti() != null) {
                blacklistAccessToken(token.getAccessJti(), jwtProperties.getAccessTokenTtl());
              }
            });
  }

  @Transactional
  public void requestPasswordReset(String email) {
    // Enumeration korumasi: caller (controller) her durumda AYNI generic yaniti dondurur; bu
    // metot kayitli olmayan e-posta icin sessizce hicbir sey yapmaz.
    userRepository
        .findByEmail(EmailAddresses.canonical(email))
        .filter(user -> passwordResetMailBudget.tryAcquire(user.getId()))
        .ifPresent(
            user -> issueVerificationToken(user.getId(), "PASSWORD_RESET", Duration.ofMinutes(30)));
  }

  /**
   * Kimlikli kullanicinin kendi dogrulama postasini yeniden istemesi. Dogrulanmis hesapta ya da
   * butce doluyken sessizce hicbir sey yapmaz (caller her durumda 202 doner).
   */
  @Transactional
  public void resendVerificationEmail(UUID userId) {
    userRepository
        .findById(userId)
        .filter(user -> !user.isEmailVerified())
        .filter(user -> passwordResetMailBudget.tryAcquireVerification(user.getId()))
        .ifPresent(
            user ->
                issueVerificationToken(user.getId(), "EMAIL_VERIFICATION", Duration.ofHours(24)));
  }

  @Transactional
  public void confirmPasswordReset(String rawToken, String newRawPassword) {
    requireMinLength(newRawPassword);
    VerificationToken token = consumeVerificationToken(rawToken, "PASSWORD_RESET");
    User user = requireUser(token.getUserId());
    user.changePasswordHash(passwordEncoder.encode(newRawPassword));
    userRepository.save(user);
    revokeAllFamiliesForUser(user.getId());
    emailNotificationPublisher.publishSecurityAlert(user.getId(), "password_changed");
    SecurityAuditLog.safeRecord(
        auditLog, SecurityAuditEvent.PASSWORD_RESET_COMPLETED, user.getId(), Map.of());
  }

  private void auditLoginFailed(String email, String clientIp, String reason) {
    try {
      UUID userId = userRepository.findByEmail(email).map(User::getId).orElse(null);
      SecurityAuditLog.safeRecord(
          auditLog,
          SecurityAuditEvent.LOGIN_FAILED,
          userId,
          Map.of("ip", String.valueOf(clientIp), "reason", reason));
    } catch (RuntimeException e) {
      // Audit asil akisi bozmaz.
    }
  }

  @Transactional
  public void verifyEmail(String rawToken) {
    VerificationToken token = consumeVerificationToken(rawToken, "EMAIL_VERIFICATION");
    User user = requireUser(token.getUserId());
    user.markEmailVerified();
    userRepository.save(user);
  }

  private LoginResult issueTokenPair(User user, UUID familyId) {
    syncSystemAdminFlag(user);
    String jti = UUID.randomUUID().toString();
    List<String> roles = user.isSystemAdmin() ? List.of("USER", "SYSTEM_ADMIN") : List.of("USER");
    String accessToken = jwtService.issueAccessToken(user.getId(), roles, jti);

    String rawRefresh = TokenHasher.generateRawToken();
    RefreshToken refreshToken =
        RefreshToken.issue(
            UUID.randomUUID(),
            user.getId(),
            familyId,
            TokenHasher.sha256Hex(rawRefresh),
            jti,
            Instant.now().plus(jwtProperties.getRefreshTokenTtl()));
    refreshTokenRepository.save(refreshToken);

    return new LoginResult(accessToken, rawRefresh, jwtProperties.getRefreshTokenTtl());
  }

  private LoginResult reissueWithinGracePeriod(RefreshToken usedToken) {
    // Cift sekme toleransi (Bolum 1.1.1): grace period icinde ayni token tekrar geldiyse aile
    // iptal edilmez; ayni aile icin basitce yeni bir cift uretilir (30sn'lik dar pencerede
    // fazladan bir cift uretimi kabul edilebilir bir maliyettir).
    User user = requireUser(usedToken.getUserId());
    return issueTokenPair(user, usedToken.getFamilyId());
  }

  private void revokeAllFamiliesForUser(UUID userId) {
    List<RefreshToken> tokens = refreshTokenRepository.findByUserId(userId);
    for (RefreshToken t : tokens) {
      t.revoke();
    }
    refreshTokenRepository.saveAll(tokens);
  }

  /**
   * SystemAdminProperties javadoc'u — env listesindeki e-postalar ilk girişte kalici olarak
   * isaretlenir.
   */
  private void syncSystemAdminFlag(User user) {
    if (!user.isSystemAdmin() && isConfiguredSystemAdmin(user.getEmail())) {
      user.grantSystemAdmin();
      userRepository.save(user);
    }
  }

  /** SEC-14: env listesindeki degerler de kanoniklestirilerek karsilastirilir. */
  private boolean isConfiguredSystemAdmin(String email) {
    String canonicalEmail = EmailAddresses.canonical(email);
    return systemAdminProperties.getSystemAdminEmails().stream()
        .filter(Objects::nonNull)
        .map(EmailAddresses::canonical)
        .anyMatch(canonicalEmail::equals);
  }

  private void blacklistAccessToken(String jti, Duration ttl) {
    redisTemplate.opsForValue().set("jwt:blacklist:" + jti, "1", ttl);
  }

  private RefreshToken requireValidRefreshToken(String rawRefreshToken) {
    String hash = TokenHasher.sha256Hex(rawRefreshToken);
    RefreshToken token =
        refreshTokenRepository
            .findByTokenHash(hash)
            .orElseThrow(() -> new BusinessRuleException("Geçersiz oturum yenileme anahtarı."));
    if (token.isRevoked()) {
      throw new BusinessRuleException("Geçersiz oturum yenileme anahtarı.");
    }
    if (token.getExpiresAt().isBefore(Instant.now())) {
      throw new BusinessRuleException("Oturum yenileme anahtarı süresi dolmuş.");
    }
    return token;
  }

  private VerificationToken consumeVerificationToken(String rawToken, String expectedPurpose) {
    String hash = TokenHasher.sha256Hex(rawToken);
    VerificationToken token =
        verificationTokenRepository
            .findByTokenHash(hash)
            .filter(t -> t.getPurpose().equals(expectedPurpose))
            .orElseThrow(() -> new BusinessRuleException("Geçersiz veya süresi dolmuş bağlantı."));
    if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
      throw new BusinessRuleException("Geçersiz veya süresi dolmuş bağlantı.");
    }
    token.markUsed(Instant.now());
    verificationTokenRepository.save(token);
    return token;
  }

  private void issueVerificationToken(UUID userId, String purpose, Duration ttl) {
    String rawToken = TokenHasher.generateRawToken();
    VerificationToken token =
        VerificationToken.issue(
            UUID.randomUUID(),
            userId,
            TokenHasher.sha256Hex(rawToken),
            purpose,
            Instant.now().plus(ttl));
    verificationTokenRepository.save(token);
    emailNotificationPublisher.publishVerificationEmail(userId, purpose, rawToken);
  }

  private User requireUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new BusinessRuleException("Kullanıcı bulunamadı."));
  }

  private static void requireMinLength(String rawPassword) {
    if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
      throw new BusinessRuleException(
          "Parola en az " + MIN_PASSWORD_LENGTH + " karakter olmalıdır.");
    }
  }
}
