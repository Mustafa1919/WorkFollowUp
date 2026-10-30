package com.app.tracker.core.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.tracker.core.web.ClientIpProperties;
import com.app.tracker.core.web.ClientIpResolver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

  private final RateLimiter limiter = mock(RateLimiter.class);
  private final ClientIpResolver ipResolver = new ClientIpResolver(new ClientIpProperties());

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private RateLimitFilter filter(RateLimitProperties props) {
    return new RateLimitFilter(limiter, ipResolver, props);
  }

  private RateLimitFilter filter() {
    return filter(new RateLimitProperties());
  }

  private static MockHttpServletRequest post(String path) {
    MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
    r.setRequestURI(path);
    r.setRemoteAddr("127.0.0.1");
    return r;
  }

  private static void authenticate(UUID userId) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("USER"))));
  }

  private static RateLimiter.Decision deny(long retry) {
    return new RateLimiter.Decision(false, 99, retry);
  }

  @Test
  void ipRule_usesResolvedIpInKey_andPassesWhenAllowed() throws Exception {
    when(limiter.hit(anyString(), anyLong(), any())).thenReturn(RateLimiter.Decision.allow(1));
    MockHttpServletRequest req = post("/api/v1/auth/login");
    req.addHeader("X-Forwarded-For", "198.51.100.7");
    AtomicBoolean called = new AtomicBoolean();
    filter().doFilter(req, new MockHttpServletResponse(), (a, b) -> called.set(true));
    assertTrue(called.get());
    verify(limiter).hit("rate:login:ip:198.51.100.7", 20, Duration.ofMinutes(1));
  }

  @Test
  void denied_writesProblemJsonWithoutChain() throws Exception {
    when(limiter.hit(anyString(), anyLong(), any())).thenReturn(deny(42));
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicBoolean called = new AtomicBoolean();
    filter().doFilter(post("/api/v1/auth/register"), res, (a, b) -> called.set(true));
    assertFalse(called.get());
    assertEquals(429, res.getStatus());
    assertEquals("42", res.getHeader("Retry-After"));
    assertTrue(res.getContentType().startsWith("application/problem+json"));
    String body = res.getContentAsString();
    assertTrue(body.contains("\"type\":\"https://api.app.com/errors/rate-limited\""), body);
    assertTrue(body.contains("\"title\":\"Cok Fazla Istek\""), body);
    assertTrue(body.contains("\"status\":429"), body);
    assertFalse(body.contains("127.0.0.1") || body.contains("rate:"), body);
    assertNull(res.getErrorMessage());
  }

  @Test
  void userRule_keyedByPrincipalUuid() throws Exception {
    when(limiter.hit(anyString(), anyLong(), any())).thenReturn(RateLimiter.Decision.allow(1));
    UUID user = UUID.randomUUID();
    authenticate(user);
    MockHttpServletRequest req = post("/api/v1/workspaces/members/invitations");
    req.addHeader("X-Forwarded-For", "198.51.100.8");
    filter().doFilter(req, new MockHttpServletResponse(), (a, b) -> {});
    verify(limiter).hit("rate:invitation:user:" + user, 30, Duration.ofHours(1));
  }

  @Test
  void userRule_anonymous_skipsLimiter() throws Exception {
    AtomicBoolean called = new AtomicBoolean();
    filter()
        .doFilter(
            post("/api/v1/feedback"), new MockHttpServletResponse(), (a, b) -> called.set(true));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "k", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    filter().doFilter(post("/api/v1/feedback"), new MockHttpServletResponse(), (a, b) -> {});
    assertTrue(called.get());
    verify(limiter, never()).hit(anyString(), anyLong(), any());
  }

  @Test
  void unmatchedRequests_doNotTouchLimiter() throws Exception {
    RateLimitFilter f = filter();
    f.doFilter(post("/api/v1/auth/refresh"), new MockHttpServletResponse(), (a, b) -> {});
    MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/auth/login");
    get.setRequestURI("/api/v1/auth/login");
    f.doFilter(get, new MockHttpServletResponse(), (a, b) -> {});
    f.doFilter(post("/api/v1/auth/login/"), new MockHttpServletResponse(), (a, b) -> {});
    f.doFilter(post("/api/v1/feedbackx"), new MockHttpServletResponse(), (a, b) -> {});
    verify(limiter, never()).hit(anyString(), anyLong(), any());
  }

  @Test
  void encodedAndMatrixPathVariants_stillMatch() throws Exception {
    when(limiter.hit(anyString(), anyLong(), any())).thenReturn(RateLimiter.Decision.allow(1));
    RateLimitFilter f = filter();
    f.doFilter(post("/api/v1/auth/logi%6E"), new MockHttpServletResponse(), (a, b) -> {});
    f.doFilter(post("/api/v1/auth/login;x=1"), new MockHttpServletResponse(), (a, b) -> {});
    verify(limiter, org.mockito.Mockito.times(2))
        .hit(eq("rate:login:ip:127.0.0.1"), eq(20L), eq(Duration.ofMinutes(1)));
  }

  @Test
  void contextPath_isStripped() throws Exception {
    when(limiter.hit(anyString(), anyLong(), any())).thenReturn(RateLimiter.Decision.allow(1));
    MockHttpServletRequest req = post("/app/api/v1/auth/login");
    req.setContextPath("/app");
    filter().doFilter(req, new MockHttpServletResponse(), (a, b) -> {});
    verify(limiter).hit(eq("rate:login:ip:127.0.0.1"), anyLong(), any());
  }

  @Test
  void disabled_appliesNoRule() throws Exception {
    RateLimitProperties props = new RateLimitProperties();
    props.setEnabled(false);
    AtomicBoolean called = new AtomicBoolean();
    filter(props)
        .doFilter(
            post("/api/v1/auth/login"), new MockHttpServletResponse(), (a, b) -> called.set(true));
    assertTrue(called.get());
    verify(limiter, never()).hit(anyString(), anyLong(), any());
  }

  @Test
  void overrides_replaceOnlyGivenFields() throws Exception {
    when(limiter.hit(anyString(), anyLong(), any())).thenReturn(RateLimiter.Decision.allow(1));
    RateLimitProperties props = new RateLimitProperties();
    RateLimitProperties.RuleSettings invitation = new RateLimitProperties.RuleSettings();
    invitation.setLimit(3L);
    RateLimitProperties.RuleSettings login = new RateLimitProperties.RuleSettings();
    login.setWindow(Duration.ofSeconds(10));
    props.setRules(java.util.Map.of("invitation", invitation, "login", login));
    RateLimitFilter f = filter(props);

    UUID user = UUID.randomUUID();
    authenticate(user);
    f.doFilter(
        post("/api/v1/workspaces/members/invitations"),
        new MockHttpServletResponse(),
        (a, b) -> {});
    f.doFilter(post("/api/v1/auth/login"), new MockHttpServletResponse(), (a, b) -> {});
    verify(limiter).hit("rate:invitation:user:" + user, 3, Duration.ofHours(1));
    verify(limiter).hit("rate:login:ip:127.0.0.1", 20, Duration.ofSeconds(10));
  }
}
