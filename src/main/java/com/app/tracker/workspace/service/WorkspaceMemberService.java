package com.app.tracker.workspace.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.tenancy.TenantContext;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.workspace.dto.WorkspaceMemberResponse;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workspace uyeligi yonetimi — ADMIN, ONCEDEN kayitli bir kullaniciyi e-postasindan bulup ANINDA
 * ekler (kabul beklemez). Dalga 1.4'ten itibaren KAYITSIZ e-postalar icin token'li bir davet
 * alternatifi de var (bkz. {@link WorkspaceInvitationService}); bu iki yol birbirini DISLAMAZ —
 * ADMIN zaten kayitli bir tanidigini beklemeden eklemek isterse bunu, kayitsiz/bilinmeyen birini
 * davet etmek isterse onu kullanir.
 *
 * <p>{@code workspace_users} VE {@code users} RLS'e TABI DEGILDIR (V2 notu — workspace secimi
 * tenant context kurulmadan once bu tablolar uzerinden yapilir); bu yuzden her sorguda {@code
 * workspaceId} TenantContext'ten acikca alinip filtreye ELLE verilir — RLS'in normalde otomatik
 * yaptigi izolasyonu burada kod yapmak ZORUNDADIR (aksi halde bir ADMIN baska workspace'in
 * uyeligini gorebilir/degistirebilirdi).
 */
@Service
public class WorkspaceMemberService {

  private static final Set<String> VALID_ROLES =
      Set.of(
          WorkspaceRole.ADMIN,
          WorkspaceRole.MANAGER,
          WorkspaceRole.DEVELOPER,
          WorkspaceRole.VIEWER);

  private final WorkspaceUserRepository workspaceUserRepository;
  private final UserRepository userRepository;

  public WorkspaceMemberService(
      WorkspaceUserRepository workspaceUserRepository, UserRepository userRepository) {
    this.workspaceUserRepository = workspaceUserRepository;
    this.userRepository = userRepository;
  }

  /**
   * Bilinmeyen e-posta 404 ("kullanici bulunamadi") doner — bu, ADMIN'e sinirli bir kullanici
   * varlik sizintisidir (kullanici enumeration); yalniz workspace ADMIN'i cagirabildigi icin (bkz.
   * controller {@code @PreAuthorize}) kabul edilebilir bir risk olarak degerlendirildi,
   * genel/anonim bir uc nokta olsaydi (parola sifirlama gibi) bu KABUL EDILMEZDI.
   */
  @Transactional
  public WorkspaceMemberResponse addMember(String email, String role) {
    UUID workspaceId = requireWorkspace();
    validateRole(role);
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("Kullanici bulunamadi."));
    if (workspaceUserRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId()).isPresent()) {
      throw new BusinessRuleException("Kullanici zaten bu workspace'in uyesi.");
    }
    workspaceUserRepository.save(WorkspaceUser.of(workspaceId, user.getId(), role));
    return toResponse(user, role);
  }

  @Transactional(readOnly = true)
  public List<WorkspaceMemberResponse> listMembers() {
    UUID workspaceId = requireWorkspace();
    List<WorkspaceUser> memberships = workspaceUserRepository.findByWorkspaceId(workspaceId);
    Map<UUID, User> usersById =
        userRepository
            .findAllById(memberships.stream().map(WorkspaceUser::getUserId).toList())
            .stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
    return memberships.stream()
        .filter(m -> usersById.containsKey(m.getUserId()))
        .map(m -> toResponse(usersById.get(m.getUserId()), m.getRole()))
        .sorted(Comparator.comparing(WorkspaceMemberResponse::email))
        .toList();
  }

  /** Son ADMIN'i MANAGER/DEVELOPER/VIEWER'a dusurmek reddedilir (workspace sahipsiz kalmasin). */
  @Transactional
  public WorkspaceMemberResponse changeRole(UUID userId, String newRole) {
    UUID workspaceId = requireWorkspace();
    validateRole(newRole);
    WorkspaceUser member = requireMember(workspaceId, userId);
    if (WorkspaceRole.ADMIN.equals(member.getRole()) && !WorkspaceRole.ADMIN.equals(newRole)) {
      rejectIfLastAdmin(workspaceId);
    }
    member.changeRole(newRole);
    workspaceUserRepository.save(member);
    return toResponse(requireUser(userId), newRole);
  }

  /** Son ADMIN'i cikarmak reddedilir. */
  @Transactional
  public void removeMember(UUID userId) {
    UUID workspaceId = requireWorkspace();
    WorkspaceUser member = requireMember(workspaceId, userId);
    if (WorkspaceRole.ADMIN.equals(member.getRole())) {
      rejectIfLastAdmin(workspaceId);
    }
    workspaceUserRepository.delete(member);
  }

  private void rejectIfLastAdmin(UUID workspaceId) {
    if (workspaceUserRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN) <= 1) {
      throw new BusinessRuleException(
          "Workspace'in son ADMIN'i cikarilamaz veya rolu degistirilemez.");
    }
  }

  private static WorkspaceMemberResponse toResponse(User user, String role) {
    return new WorkspaceMemberResponse(user.getId(), user.getEmail(), user.getFullName(), role);
  }

  private static void validateRole(String role) {
    if (!VALID_ROLES.contains(role)) {
      throw new BusinessRuleException("Gecersiz rol: " + role);
    }
  }

  private WorkspaceUser requireMember(UUID workspaceId, UUID userId) {
    return workspaceUserRepository
        .findByWorkspaceIdAndUserId(workspaceId, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Uye bulunamadi."));
  }

  private User requireUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("Kullanici bulunamadi."));
  }

  private static UUID requireWorkspace() {
    UUID workspaceId = TenantContext.getWorkspaceId();
    if (workspaceId == null) {
      throw new BusinessRuleException("Once bir workspace secmelisiniz (X-Workspace-Id header).");
    }
    return workspaceId;
  }
}
