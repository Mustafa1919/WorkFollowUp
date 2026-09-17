package com.app.tracker.core.security.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SECURITY_AND_EXCEPTIONS_DESIGN.md Bolum 1.4.2/1.4.3 — parola sifirlama VE e-posta dogrulama aynı
 * mekanizmayi paylasir; {@code purpose} ile ayrilir ("PASSWORD_RESET" / "EMAIL_VERIFICATION").
 */
@Entity
@Table(name = "verification_tokens")
@Getter
@NoArgsConstructor
public class VerificationToken {

  @Id private UUID id;

  private UUID userId;

  private String tokenHash;

  private String purpose;

  private Instant expiresAt;

  private Instant usedAt;

  private Instant createdAt;

  public static VerificationToken issue(
      UUID id, UUID userId, String tokenHash, String purpose, Instant expiresAt) {
    VerificationToken token = new VerificationToken();
    token.id = id;
    token.userId = userId;
    token.tokenHash = tokenHash;
    token.purpose = purpose;
    token.expiresAt = expiresAt;
    token.createdAt = Instant.now();
    return token;
  }

  public void markUsed(Instant when) {
    this.usedAt = when;
  }
}
