package com.app.tracker.core.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SystemAdminProperties javadoc'u — {@code app.security.system-admin-emails} listesindeki bir
 * e-posta ilk girişte {@code users.is_system_admin=true} olarak isaretlenmeli ve JWT'ye {@code
 * SYSTEM_ADMIN} authority'si eklenmelidir; listede OLMAYAN bir kullanici icin ikisi de olmamali.
 */
@SpringBootTest
class SystemAdminIntegrationTest extends AbstractIntegrationTest {

  private static final String ADMIN_EMAIL = "owner@tracker.local";

  @Autowired private AuthService authService;
  @Autowired private JwtService jwtService;
  @Autowired private UserRepository userRepository;

  @DynamicPropertySource
  static void registerAdminEmail(DynamicPropertyRegistry registry) {
    registry.add("app.security.system-admin-emails", () -> ADMIN_EMAIL);
  }

  @Test
  void configuredEmailIsPromotedToSystemAdminOnLogin() {
    authService.register(ADMIN_EMAIL, "correct-horse-battery", "Owner");

    AuthService.LoginResult result =
        authService.login(ADMIN_EMAIL, "correct-horse-battery", "127.0.0.1");

    DecodedJwt decoded = jwtService.verify(result.accessToken());
    assertTrue(decoded.roles().contains("SYSTEM_ADMIN"));

    User persisted = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
    assertTrue(persisted.isSystemAdmin());
  }

  @Test
  void unconfiguredEmailStaysRegularUser() {
    String email = "regular@tracker.local";
    authService.register(email, "correct-horse-battery", "Regular");

    AuthService.LoginResult result = authService.login(email, "correct-horse-battery", "127.0.0.1");

    DecodedJwt decoded = jwtService.verify(result.accessToken());
    assertFalse(decoded.roles().contains("SYSTEM_ADMIN"));

    User persisted = userRepository.findByEmail(email).orElseThrow();
    assertFalse(persisted.isSystemAdmin());
  }
}
