package com.app.tracker.accesstoken.service;

import com.app.tracker.accesstoken.model.PersonalAccessToken;
import com.app.tracker.accesstoken.repository.PersonalAccessTokenRepository;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.PatTokenFormat;
import com.app.tracker.core.security.TokenHasher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0018 -- kisisel erisim token'lari (PAT). Duz metin token SADECE olusturma yanitinda bir defa
 * donulur, DB'ye hic yazilmaz (refresh_tokens/verification_tokens ile AYNI ilke, bkz. TokenHasher
 * javadoc'u).
 */
@Service
public class PersonalAccessTokenService {

  /** V1'de kapsam/scope sistemi YOK -- ADR-0018 Karar 2, kullaniciyla AYNI global rolleri tasir. */
  private static final Duration MAX_TTL = Duration.ofDays(365);

  private final PersonalAccessTokenRepository repository;

  public PersonalAccessTokenService(PersonalAccessTokenRepository repository) {
    this.repository = repository;
  }

  public record IssuedToken(PersonalAccessToken entity, String rawToken) {}

  @Transactional
  public IssuedToken create(UUID userId, String name, Duration ttl) {
    if (name == null || name.isBlank()) {
      throw new BusinessRuleException("Token adi bos olamaz.");
    }
    if (ttl != null && (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0)) {
      throw new BusinessRuleException("Gecerlilik suresi 1 gun ile 365 gun arasinda olmalidir.");
    }
    String rawToken = PatTokenFormat.generateRawToken();
    Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
    PersonalAccessToken token =
        PersonalAccessToken.issue(
            UUID.randomUUID(),
            userId,
            name.trim(),
            TokenHasher.sha256Hex(rawToken),
            PatTokenFormat.previewOf(rawToken),
            expiresAt);
    return new IssuedToken(repository.save(token), rawToken);
  }

  @Transactional(readOnly = true)
  public List<PersonalAccessToken> list(UUID userId) {
    return repository.findAllByUserIdOrderByCreatedAtDesc(userId);
  }

  @Transactional
  public void revoke(UUID userId, UUID tokenId) {
    PersonalAccessToken token =
        repository
            .findById(tokenId)
            .filter(t -> t.getUserId().equals(userId))
            .orElseThrow(() -> new ResourceNotFoundException("Token bulunamadi."));
    token.revoke();
  }

  /**
   * JwtAuthenticationFilter'daki DEGIL, PatAuthenticationFilter'daki cagiri noktasi -- DB
   * okuma+yazma (last_used_at) icerdigi icin gercek bir @Transactional servis metodu SART
   * (Mimari.md'deki tekrar eden ders: repository'yi tx sinirinin disinda cagirmak okumada SESSIZ
   * bos, yazmada TransactionRequiredException doner).
   */
  @Transactional
  public Optional<AuthenticatedPat> authenticate(String rawToken) {
    return repository
        .findByTokenHash(TokenHasher.sha256Hex(rawToken))
        .filter(token -> token.isActive(Instant.now()))
        .map(
            token -> {
              token.markUsed(Instant.now());
              return new AuthenticatedPat(token.getId(), token.getUserId());
            });
  }
}
