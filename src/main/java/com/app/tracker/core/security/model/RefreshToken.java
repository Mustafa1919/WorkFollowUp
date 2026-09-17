package com.app.tracker.core.security.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.1.1 — Refresh Token Rotation + Reuse Detection. Duz
 * token asla saklanmaz, sadece {@code tokenHash} (SHA-256). {@code accessJti}, aile iptali anında
 * ilgili access token'i da Redis kara listeye ekleyebilmek icin tutulur.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor
public class RefreshToken {

  @Id private UUID id;

  private UUID userId;

  private UUID familyId;

  private String tokenHash;

  private String accessJti;

  private Instant expiresAt;

  private Instant usedAt;

  private boolean revoked;

  private Instant createdAt;

  public static RefreshToken issue(
      UUID id, UUID userId, UUID familyId, String tokenHash, String accessJti, Instant expiresAt) {
    RefreshToken token = new RefreshToken();
    token.id = id;
    token.userId = userId;
    token.familyId = familyId;
    token.tokenHash = tokenHash;
    token.accessJti = accessJti;
    token.expiresAt = expiresAt;
    token.revoked = false;
    token.createdAt = Instant.now();
    return token;
  }

  public void markUsed(Instant when) {
    this.usedAt = when;
  }

  public void revoke() {
    this.revoked = true;
  }
}
