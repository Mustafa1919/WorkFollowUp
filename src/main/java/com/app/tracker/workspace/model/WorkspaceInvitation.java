package com.app.tracker.workspace.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * V25 — {@code verification_tokens} ile AYNI desen (RLS'e tabi degil, {@code tokenHash} SHA-256).
 * {@code status} PENDING/ACCEPTED/REVOKED; "suresi dolmus" ayri bir durum degil, {@code expiresAt}
 * gecmiste kalan PENDING satir olarak servis katmaninda turetilir (bkz.
 * WorkspaceInvitationService).
 */
@Entity
@Table(name = "workspace_invitations")
@Getter
@NoArgsConstructor
public class WorkspaceInvitation {

  public static final String PENDING = "PENDING";
  public static final String ACCEPTED = "ACCEPTED";
  public static final String REVOKED = "REVOKED";

  @Id private UUID id;

  private UUID workspaceId;

  private String email;

  private String role;

  private String tokenHash;

  private UUID invitedBy;

  private String status;

  private Instant expiresAt;

  private Instant acceptedAt;

  private Instant createdAt;

  public static WorkspaceInvitation issue(
      UUID id,
      UUID workspaceId,
      String email,
      String role,
      String tokenHash,
      UUID invitedBy,
      Instant expiresAt) {
    WorkspaceInvitation invitation = new WorkspaceInvitation();
    invitation.id = id;
    invitation.workspaceId = workspaceId;
    invitation.email = email;
    invitation.role = role;
    invitation.tokenHash = tokenHash;
    invitation.invitedBy = invitedBy;
    invitation.status = PENDING;
    invitation.expiresAt = expiresAt;
    invitation.createdAt = Instant.now();
    return invitation;
  }

  public boolean isPending() {
    return PENDING.equals(status);
  }

  public boolean isExpired(Instant now) {
    return isPending() && expiresAt.isBefore(now);
  }

  public void accept(Instant when) {
    this.status = ACCEPTED;
    this.acceptedAt = when;
  }

  public void revoke() {
    this.status = REVOKED;
  }
}
