package com.app.tracker.workspace.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.notification.EmailNotificationPublisher;
import com.app.tracker.core.security.CurrentUser;
import com.app.tracker.core.security.TokenHasher;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.workspace.dto.AcceptInvitationResponse;
import com.app.tracker.workspace.dto.InvitationPreviewResponse;
import com.app.tracker.workspace.dto.WorkspaceInvitationResponse;
import com.app.tracker.workspace.model.Workspace;
import com.app.tracker.workspace.model.WorkspaceInvitation;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceInvitationRepository;
import com.app.tracker.workspace.repository.WorkspaceRepository;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Token'li davet (Dalga 1.4) — {@code AddWorkspaceMemberRequest}/{@code WorkspaceMemberService}
 * (ONCEDEN kayitli kullaniciyi aninda ekler) ile AYNI amaca hizmet eden, ama KAYITSIZ e-postalari
 * da kapsayan ikinci bir yol. {@code workspace_invitations} RLS'e tabi DEGIL (V25 notu) —
 * workspaceId her sorguda acikca verilir (WorkspaceMemberService ile AYNI desen).
 *
 * <p>{@code preview}/{@code accept} bilerek workspace context'ten BAGIMSIZDIR: token, hangi
 * workspace'e ait oldugunu KENDI tasir (davet linkine tiklayan kisi henuz o workspace'in uyesi
 * degildir, X-Workspace-Id header'i gonderemez).
 */
@Service
public class WorkspaceInvitationService {

  private static final Duration TTL = Duration.ofDays(7);
  private static final Set<String> VALID_ROLES =
      Set.of(
          WorkspaceRole.ADMIN,
          WorkspaceRole.MANAGER,
          WorkspaceRole.DEVELOPER,
          WorkspaceRole.VIEWER);

  private final WorkspaceInvitationRepository invitationRepository;
  private final WorkspaceUserRepository workspaceUserRepository;
  private final WorkspaceRepository workspaceRepository;
  private final UserRepository userRepository;
  private final EmailNotificationPublisher emailNotificationPublisher;

  public WorkspaceInvitationService(
      WorkspaceInvitationRepository invitationRepository,
      WorkspaceUserRepository workspaceUserRepository,
      WorkspaceRepository workspaceRepository,
      UserRepository userRepository,
      EmailNotificationPublisher emailNotificationPublisher) {
    this.invitationRepository = invitationRepository;
    this.workspaceUserRepository = workspaceUserRepository;
    this.workspaceRepository = workspaceRepository;
    this.userRepository = userRepository;
    this.emailNotificationPublisher = emailNotificationPublisher;
  }

  /**
   * Ayni workspace+e-posta icin bekleyen bir davet varsa ONCE REVOKE edilir, sonra yenisi uretilir
   * — "yeniden gonder" (resend) ayri bir uc nokta DEGIL, tekrar davet etmek yeterli.
   */
  @Transactional
  public WorkspaceInvitationResponse invite(String email, String role) {
    UUID workspaceId = requireWorkspace();
    UUID inviterId = CurrentUser.id();
    validateRole(role);
    String normalizedEmail = email.trim();

    userRepository
        .findByEmail(normalizedEmail)
        .filter(
            u ->
                workspaceUserRepository
                    .findByWorkspaceIdAndUserId(workspaceId, u.getId())
                    .isPresent())
        .ifPresent(
            u -> {
              throw new BusinessRuleException("Kullanici zaten bu workspace'in uyesi.");
            });

    invitationRepository
        .findByWorkspaceIdAndEmailAndStatus(
            workspaceId, normalizedEmail, WorkspaceInvitation.PENDING)
        .ifPresent(WorkspaceInvitation::revoke);

    String rawToken = TokenHasher.generateRawToken();
    WorkspaceInvitation invitation =
        WorkspaceInvitation.issue(
            UUID.randomUUID(),
            workspaceId,
            normalizedEmail,
            role,
            TokenHasher.sha256Hex(rawToken),
            inviterId,
            Instant.now().plus(TTL));
    invitationRepository.save(invitation);

    Workspace workspace = requireWorkspaceEntity(workspaceId);
    emailNotificationPublisher.publishWorkspaceInvite(
        invitation.getId(), normalizedEmail, workspace.getName(), role, rawToken);

    return toResponse(invitation);
  }

  @Transactional(readOnly = true)
  public List<WorkspaceInvitationResponse> listPending() {
    UUID workspaceId = requireWorkspace();
    return invitationRepository
        .findByWorkspaceIdAndStatusOrderByCreatedAtDesc(workspaceId, WorkspaceInvitation.PENDING)
        .stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional
  public void revoke(UUID invitationId) {
    UUID workspaceId = requireWorkspace();
    WorkspaceInvitation invitation =
        invitationRepository
            .findById(invitationId)
            .filter(inv -> inv.getWorkspaceId().equals(workspaceId))
            .orElseThrow(() -> new ResourceNotFoundException("Davet bulunamadi."));
    if (!invitation.isPending()) {
      throw new BusinessRuleException("Yalniz bekleyen bir davet iptal edilebilir.");
    }
    invitation.revoke();
  }

  @Transactional(readOnly = true)
  public InvitationPreviewResponse preview(String rawToken) {
    WorkspaceInvitation invitation = requireByToken(rawToken);
    Workspace workspace = requireWorkspaceEntity(invitation.getWorkspaceId());
    return new InvitationPreviewResponse(
        workspace.getName(), invitation.getEmail(), invitation.getRole(), deriveStatus(invitation));
  }

  /**
   * Cagiran ZATEN kimlik dogrulanmis olmali (controller {@code @PreAuthorize}/CurrentUser). Davet
   * e-postasiyla hesabin e-postasi (buyuk/kucuk harf duyarsiz) ESLESMEZSE reddedilir — aksi halde
   * e-postayi baskasindan alan/tahmin eden biri kendi hesabiyla davete "katilabilirdi".
   */
  @Transactional
  public AcceptInvitationResponse accept(String rawToken, UUID userId) {
    WorkspaceInvitation invitation = requireByToken(rawToken);
    if (!invitation.isPending() || invitation.isExpired(Instant.now())) {
      throw new BusinessRuleException("Gecersiz veya suresi dolmus davet.");
    }
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessRuleException("Kullanici bulunamadi."));
    if (!invitation.getEmail().equalsIgnoreCase(user.getEmail())) {
      throw new BusinessRuleException(
          "Bu davet farkli bir e-posta adresine gonderildi. Lutfen "
              + invitation.getEmail()
              + " ile giris yap.");
    }

    UUID workspaceId = invitation.getWorkspaceId();
    if (workspaceUserRepository.findByWorkspaceIdAndUserId(workspaceId, userId).isEmpty()) {
      workspaceUserRepository.save(WorkspaceUser.of(workspaceId, userId, invitation.getRole()));
    }
    invitation.accept(Instant.now());

    Workspace workspace = requireWorkspaceEntity(workspaceId);
    return new AcceptInvitationResponse(workspaceId, workspace.getName(), invitation.getRole());
  }

  private WorkspaceInvitation requireByToken(String rawToken) {
    return invitationRepository
        .findByTokenHash(TokenHasher.sha256Hex(rawToken))
        .orElseThrow(() -> new BusinessRuleException("Gecersiz davet baglantisi."));
  }

  private String deriveStatus(WorkspaceInvitation invitation) {
    if (invitation.isExpired(Instant.now())) {
      return "EXPIRED";
    }
    return invitation.getStatus();
  }

  private Workspace requireWorkspaceEntity(UUID workspaceId) {
    return workspaceRepository
        .findById(workspaceId)
        .orElseThrow(() -> new ResourceNotFoundException("Workspace bulunamadi."));
  }

  private WorkspaceInvitationResponse toResponse(WorkspaceInvitation invitation) {
    return new WorkspaceInvitationResponse(
        invitation.getId(),
        invitation.getEmail(),
        invitation.getRole(),
        deriveStatus(invitation),
        invitation.getExpiresAt(),
        invitation.getCreatedAt());
  }

  private static void validateRole(String role) {
    if (!VALID_ROLES.contains(role)) {
      throw new BusinessRuleException("Gecersiz rol: " + role);
    }
  }

  private static UUID requireWorkspace() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new BusinessRuleException("Once bir workspace secmelisiniz (X-Workspace-Id header).");
    }
    return workspaceId;
  }
}
