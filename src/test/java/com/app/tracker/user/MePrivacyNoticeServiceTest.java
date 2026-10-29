package com.app.tracker.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import com.app.tracker.user.service.MeService;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MePrivacyNoticeServiceTest {

  @Test
  void recordsInjectedClockTimeOnLoadedUser() {
    UserRepository repository = mock(UserRepository.class);
    User user = User.newUser(UUID.randomUUID(), "user@tracker.local", "hash", "User");
    when(repository.findById(user.getId())).thenReturn(Optional.of(user));
    Instant now = Instant.parse("2026-10-29T10:15:30Z");
    MeService service =
        new MeService(
            repository, mock(WorkspaceMembershipService.class), Clock.fixed(now, ZoneOffset.UTC));

    service.acceptPrivacyNotice(user.getId(), PrivacyNotice.CURRENT_VERSION);

    assertEquals(PrivacyNotice.CURRENT_VERSION, user.getPrivacyNoticeVersion());
    assertEquals(now, user.getPrivacyNoticeAcceptedAt());
  }
}
