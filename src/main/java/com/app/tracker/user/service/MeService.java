package com.app.tracker.user.service;

import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeService {

  private final UserRepository userRepository;
  private final WorkspaceMembershipService membershipService;

  public MeService(UserRepository userRepository, WorkspaceMembershipService membershipService) {
    this.userRepository = userRepository;
    this.membershipService = membershipService;
  }

  @Transactional(readOnly = true)
  public User getUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("Kullanici bulunamadi."));
  }

  /** Uye degilse bos doner; filtre bunu zaten 403'ler, savunma amacli. */
  @Transactional(readOnly = true)
  public String getRole(UUID userId, UUID workspaceId) {
    return membershipService
        .findRole(userId, workspaceId)
        .orElseThrow(() -> new ResourceNotFoundException("Workspace uyeligi bulunamadi."));
  }
}
