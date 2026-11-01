package com.app.tracker.user.service;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeService {

  private final UserRepository userRepository;
  private final WorkspaceMembershipService membershipService;
  private final Clock clock;
  private final AuthService authService;

  public MeService(
      UserRepository userRepository,
      WorkspaceMembershipService membershipService,
      Clock clock,
      AuthService authService) {
    this.authService = authService;
    this.userRepository = userRepository;
    this.membershipService = membershipService;
    this.clock = clock;
  }

  @Transactional
  public void acceptPrivacyNotice(UUID userId, String version) {
    if (!PrivacyNotice.CURRENT_VERSION.equals(version)) {
      throw new BusinessRuleException(
          "Aydınlatma metni güncellendi; sayfayı yenileyip yeniden onaylayın.");
    }
    User user = getUser(userId);
    user.acceptPrivacyNotice(version, Instant.now(clock));
  }

  @Transactional
  public void resendVerificationEmail(UUID userId) {
    authService.resendVerificationEmail(userId);
  }

  @Transactional(readOnly = true)
  public User getUser(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("Kullanıcı bulunamadı."));
  }

  /** Uye degilse bos doner; filtre bunu zaten 403'ler, savunma amacli. */
  @Transactional(readOnly = true)
  public String getRole(UUID userId, UUID workspaceId) {
    return membershipService
        .findRole(userId, workspaceId)
        .orElseThrow(() -> new ResourceNotFoundException("Çalışma alanı üyeliği bulunamadı."));
  }
}
