package com.app.tracker.accesstoken.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ADR-0018 -- kisisel erisim token'i (PAT). refresh_tokens/verification_tokens (V5) ile AYNI
 * mantik: RLS'e tabi degil, kullaniciya gore izole. Duz metin token ASLA saklanmaz, sadece SHA-256
 * hash'i ({@code TokenHasher}) -- {@code tokenPreview} sadece listede kullanici hangi token
 * oldugunu ayirt edebilsin diye ilk birkac karakteri tasir, geri kalanini kurtarmaya yetmez.
 */
@Entity
@Table(name = "personal_access_tokens")
@Getter
@NoArgsConstructor
public class PersonalAccessToken {

  @Id private UUID id;

  private UUID userId;

  private String name;

  private String tokenHash;

  private String tokenPreview;

  private Instant lastUsedAt;

  private Instant expiresAt;

  private Instant revokedAt;

  private Instant createdAt;

  public static PersonalAccessToken issue(
      UUID id, UUID userId, String name, String tokenHash, String tokenPreview, Instant expiresAt) {
    PersonalAccessToken token = new PersonalAccessToken();
    token.id = id;
    token.userId = userId;
    token.name = name;
    token.tokenHash = tokenHash;
    token.tokenPreview = tokenPreview;
    token.expiresAt = expiresAt;
    token.createdAt = Instant.now();
    return token;
  }

  public void markUsed(Instant now) {
    this.lastUsedAt = now;
  }

  public void revoke() {
    this.revokedAt = Instant.now();
  }

  public boolean isActive(Instant now) {
    if (revokedAt != null) {
      return false;
    }
    return expiresAt == null || now.isBefore(expiresAt);
  }
}
