package com.app.tracker.workspace.service;

import com.app.tracker.workspace.dto.WorkspaceMembershipResponse;
import com.app.tracker.workspace.model.Workspace;
import com.app.tracker.workspace.model.WorkspaceUser;
import com.app.tracker.workspace.repository.WorkspaceRepository;
import com.app.tracker.workspace.repository.WorkspaceUserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Istemcinin {@code X-Workspace-Id} secebilmesi icin kullanicinin uyeliklerini listeler. Ne {@code
 * workspaces} ne {@code workspace_users} RLS'e tabidir (V2 notu); filtre yalniz {@code user_id}
 * uzerinden yapilir, bu yuzden tenant context GEREKMEZ ve header'siz cagrilir.
 */
@Service
public class WorkspaceMembershipQueryService {

  private final WorkspaceUserRepository workspaceUserRepository;
  private final WorkspaceRepository workspaceRepository;

  public WorkspaceMembershipQueryService(
      WorkspaceUserRepository workspaceUserRepository, WorkspaceRepository workspaceRepository) {
    this.workspaceUserRepository = workspaceUserRepository;
    this.workspaceRepository = workspaceRepository;
  }

  @Transactional(readOnly = true)
  public List<WorkspaceMembershipResponse> listForUser(UUID userId) {
    List<WorkspaceUser> memberships = workspaceUserRepository.findByUserId(userId);
    Map<UUID, Workspace> workspaces =
        workspaceRepository
            .findAllById(memberships.stream().map(WorkspaceUser::getWorkspaceId).toList())
            .stream()
            .collect(Collectors.toMap(Workspace::getId, Function.identity()));
    return memberships.stream()
        .filter(m -> workspaces.containsKey(m.getWorkspaceId()))
        .map(
            m -> {
              Workspace w = workspaces.get(m.getWorkspaceId());
              return new WorkspaceMembershipResponse(
                  w.getId(), w.getName(), w.getPlanType(), m.getRole());
            })
        .sorted(Comparator.comparing(WorkspaceMembershipResponse::name))
        .toList();
  }
}
