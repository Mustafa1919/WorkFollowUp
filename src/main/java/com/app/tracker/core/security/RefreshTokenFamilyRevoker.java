package com.app.tracker.core.security;

import com.app.tracker.core.notification.EmailNotificationPublisher;
import com.app.tracker.core.security.model.RefreshToken;
import com.app.tracker.core.security.repository.RefreshTokenRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh token tekrar kullanimi tespit edilince aileyi iptal eder. AuthService.refresh() ardindan
 * istisna atip rollback olsa bile iptalin kalici olmasi icin REQUIRES_NEW; self-invocation proxy'yi
 * atlayacagindan ayri bean.
 */
@Component
public class RefreshTokenFamilyRevoker {

  private final RefreshTokenRepository refreshTokenRepository;
  private final EmailNotificationPublisher emailNotificationPublisher;
  private final StringRedisTemplate redisTemplate;
  private final JwtProperties jwtProperties;

  public RefreshTokenFamilyRevoker(
      RefreshTokenRepository refreshTokenRepository,
      EmailNotificationPublisher emailNotificationPublisher,
      StringRedisTemplate redisTemplate,
      JwtProperties jwtProperties) {
    this.refreshTokenRepository = refreshTokenRepository;
    this.emailNotificationPublisher = emailNotificationPublisher;
    this.redisTemplate = redisTemplate;
    this.jwtProperties = jwtProperties;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void revokeFamily(UUID familyId) {
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
      redisTemplate
          .opsForValue()
          .set("jwt:blacklist:" + jti, "1", jwtProperties.getAccessTokenTtl());
    }
    if (userId != null) {
      emailNotificationPublisher.publishSecurityAlert(userId, "refresh_token_reuse_detected");
    }
  }
}
