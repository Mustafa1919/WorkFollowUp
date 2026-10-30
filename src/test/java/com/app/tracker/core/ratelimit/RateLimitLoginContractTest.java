package com.app.tracker.core.ratelimit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.tracker.core.exception.GlobalExceptionHandler;
import com.app.tracker.core.notification.EmailNotificationPublisher;
import com.app.tracker.core.security.AuthController;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.security.BruteForceGuard;
import com.app.tracker.core.security.CorsProperties;
import com.app.tracker.core.security.JwtProperties;
import com.app.tracker.core.security.JwtService;
import com.app.tracker.core.security.RefreshTokenFamilyRevoker;
import com.app.tracker.core.security.SystemAdminProperties;
import com.app.tracker.core.security.repository.RefreshTokenRepository;
import com.app.tracker.core.security.repository.VerificationTokenRepository;
import com.app.tracker.core.web.ClientIpProperties;
import com.app.tracker.core.web.ClientIpResolver;
import com.app.tracker.telemetry.TelemetryEventPublisher;
import com.app.tracker.user.model.User;
import com.app.tracker.user.repository.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** K1: gercek controller, AuthService, parola dogrulamasi ve hata eslemesi; Docker gerekmez. */
class RateLimitLoginContractTest {
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    UserRepository users = mock(UserRepository.class);
    BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    User user =
        User.newUser(
            UUID.randomUUID(),
            "contract@tracker.local",
            passwords.encode("correct-contract-password"),
            "Contract");
    when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    BruteForceGuard guard = mock(BruteForceGuard.class);
    when(guard.checkBeforeAttempt(anyString(), anyString()))
        .thenReturn(new BruteForceGuard.CheckResult(false, false, false));
    JwtService jwt = mock(JwtService.class);
    when(jwt.issueAccessToken(any(), anyList(), anyString())).thenReturn("contract-access-token");
    AuthService auth =
        new AuthService(
            users,
            mock(RefreshTokenRepository.class),
            mock(VerificationTokenRepository.class),
            passwords,
            jwt,
            new JwtProperties(),
            guard,
            mock(EmailNotificationPublisher.class),
            mock(StringRedisTemplate.class),
            new SystemAdminProperties(),
            mock(RefreshTokenFamilyRevoker.class),
            Clock.systemUTC());
    ClientIpResolver resolver = new ClientIpResolver(new ClientIpProperties());
    RateLimitFilter filter =
        new RateLimitFilter(
            new RateLimiter(new RateLimitUnitContractTest.CounterRedis()),
            resolver,
            new RateLimitProperties());
    mvc =
        MockMvcBuilders.standaloneSetup(new AuthController(auth, new CorsProperties(), resolver))
            .setControllerAdvice(new GlobalExceptionHandler(mock(TelemetryEventPublisher.class)))
            .addFilters(filter)
            .build();
  }

  @Test
  void K1_wrongPasswordBelowLimitKeepsBusinessRuleResponse() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .with(
                    request -> {
                      request.setRemoteAddr("127.0.0.1");
                      return request;
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"contract@tracker.local\",\"password\":\"incorrect-contract-password\"}"))
        .andExpect(status().isBadRequest()); // projede yanlis parola 400 (BusinessRuleException)
  }

  @Test
  void K1_correctPasswordBelowLimitReturns200() throws Exception {
    String body =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(
                        request -> {
                          request.setRemoteAddr("127.0.0.1");
                          return request;
                        })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"contract@tracker.local\",\"password\":\"correct-contract-password\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(body.contains("contract-access-token"));
  }
}
