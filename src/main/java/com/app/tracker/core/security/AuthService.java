package com.app.tracker.core.security;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.notification.EmailNotificationPublisher;
import com.app.tracker.core.security.model.RefreshToken;
import com.app.tracker.core.security.model.VerificationToken;
import com.app.tracker.core.security.repository.RefreshTokenRepository;
import com.app.tracker.core.security.repository.VerificationTokenRepository;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
  private final String dummyHash;

  public AuthService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      VerificationTokenRepository verificationTokenRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      JwtProperties jwtProperties,
      BruteForceGuard bruteForceGuard,
      EmailNotificationPublisher emailNotificationPublisher,
      StringRedisTemplate redisTemplate) {
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.verificationTokenRepository = verificationTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.jwtProperties = jwtProperties;
    this.bruteForceGuard = bruteForceGuard;
    this.emailNotificationPublisher = emailNotificationPublisher;
    this.redisTemplate = redisTemplate;
    // Bolum 1.4.1 Timing Attack korumasi: kayitli olmayan e-posta icin de bcrypt bir hash'e
    // karsi calissin diye sabit bir "dummy" hash onceden hesaplaniyor.
    this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-protection");
  }

  public record LoginResult(String accessToken, String refreshToken, Duration refreshTtl) {}

  @Transactional
  public User register(String email, String rawPassword, String fullName) {
    requireMinLength(rawPassword);
    userRepository
        .findByEmail(email)
        .ifPresent(
            u -> {
              throw new BusinessRuleException("Bu e-posta zaten kayitli.");
            });
    User user =
        User.newUser(UUID.randomUUID(), email, passwordEncoder.encode(rawPassword), fullName);
    userRepository.save(user);
    issueVerificationToken(user.getId(), "EMAIL_VERIFICATION", Duration.ofHours(24));
    return user;
  }

  @Transactional
  public LoginResult login(String email, String rawPassword, String clientIp) {
    BruteForceGuard.CheckResult check = bruteForceGuard.checkBeforeAttempt(email, clientIp);
    if (check.locked()) {
      throw new BusinessRuleException(
          "Hesap gecici olarak kilitlendi. Lutfen 15 dakika sonra tekrar deneyin.");
    }
    if (check.throttled()) {
      throw new BusinessRuleException(
          "Cok fazla basarisiz deneme. Lutfen biraz sonra tekrar deneyin.");
    }

    Optional<User> userOpt = userRepository.findByEmail(email);
    String hashToCheck = userOpt.map(User::getPasswordHash).orElse(dummyHash);
    boolean matches = passwordEncoder.matches(rawPassword, hashToCheck);

    if (userOpt.isEmpty() || !matches) {
      bruteForceGuard.recordFailure(email, clientIp);
      // Enumeration korumasi (Bolum 1.4.1): kullanici var/yok her durumda AYNI mesaj.
      throw new BusinessRuleException("E-posta veya sifre hatali.");
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
      revokeFamily(token.getFamilyId());
      throw new BusinessRuleException(
          "Guvenlik ihlali tespit edildi, tum oturumlar sonlandirildi. Lutfen yeniden giris yapin.");
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
        .findByEmail(email)
        .ifPresent(
            user -> issueVerificationToken(user.getId(), "PASSWORD_RESET", Duration.ofMinutes(30)));
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
  }

  @Transactional
  public void verifyEmail(String rawToken) {
    VerificationToken token = consumeVerificationToken(rawToken, "EMAIL_VERIFICATION");
    User user = requireUser(token.getUserId());
    user.markEmailVerified();
    userRepository.save(user);
  }

  private LoginResult issueTokenPair(User user, UUID familyId) {
    String jti = UUID.randomUUID().toString();
    String accessToken = jwtService.issueAccessToken(user.getId(), List.of("USER"), jti);

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

  private void revokeFamily(UUID familyId) {
    List<RefreshToken> family = refreshTokenRepository.findByFamilyId(familyId);
    List<String> jtisToBlacklist = new ArrayList<>();
    UUID userId = null;
    for (RefreshToken t : family) {
      t.revoke();
      userId = t.getUserId();
      if (t.getAccessJti() != null) {
        jtisToBlacklist.add(t.getAccessJti());
      }
    }
    refreshTokenRepository.saveAll(family);
    for (String jti : jtisToBlacklist) {
      blacklistAccessToken(jti, jwtProperties.getAccessTokenTtl());
    }
    if (userId != null) {
      emailNotificationPublisher.publishSecurityAlert(userId, "refresh_token_reuse_detected");
    }
  }

  private void revokeAllFamiliesForUser(UUID userId) {
    List<RefreshToken> tokens = refreshTokenRepository.findByUserId(userId);
    for (RefreshToken t : tokens) {
      t.revoke();
    }
    refreshTokenRepository.saveAll(tokens);
  }

  private void blacklistAccessToken(String jti, Duration ttl) {
    redisTemplate.opsForValue().set("jwt:blacklist:" + jti, "1", ttl);
  }

  private RefreshToken requireValidRefreshToken(String rawRefreshToken) {
    String hash = TokenHasher.sha256Hex(rawRefreshToken);
    RefreshToken token =
        refreshTokenRepository
            .findByTokenHash(hash)
            .orElseThrow(() -> new BusinessRuleException("Gecersiz refresh token."));
    if (token.isRevoked()) {
      throw new BusinessRuleException("Gecersiz refresh token.");
    }
    if (token.getExpiresAt().isBefore(Instant.now())) {
      throw new BusinessRuleException("Refresh token suresi dolmus.");
    }
    return token;
  }

  private VerificationToken consumeVerificationToken(String rawToken, String expectedPurpose) {
    String hash = TokenHasher.sha256Hex(rawToken);
    VerificationToken token =
        verificationTokenRepository
            .findByTokenHash(hash)
            .filter(t -> t.getPurpose().equals(expectedPurpose))
            .orElseThrow(() -> new BusinessRuleException("Gecersiz veya suresi dolmus token."));
    if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
      throw new BusinessRuleException("Gecersiz veya suresi dolmus token.");
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
        .orElseThrow(() -> new BusinessRuleException("Kullanici bulunamadi."));
  }

  private static void requireMinLength(String rawPassword) {
    if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
      throw new BusinessRuleException(
          "Parola en az " + MIN_PASSWORD_LENGTH + " karakter olmalidir.");
    }
  }
}
