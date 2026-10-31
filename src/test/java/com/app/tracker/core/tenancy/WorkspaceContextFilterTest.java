package com.app.tracker.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class WorkspaceContextFilterTest {

  private final WorkspaceMembershipService membership = mock(WorkspaceMembershipService.class);
  private final WorkspaceContextFilter filter = new WorkspaceContextFilter(membership);
  private final UUID userId = UUID.randomUUID();

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    TenantContext.clear();
  }

  private void authenticate() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of()));
  }

  private MockHttpServletResponse run(MockHttpServletRequest req, AtomicBoolean chained)
      throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, (rq, rs) -> chained.set(true));
    return res;
  }

  private MockHttpServletRequest withHeaders(String... values) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/projects");
    for (String v : values) {
      r.addHeader("X-Workspace-Id", v);
    }
    return r;
  }

  private void assertBadRequest(MockHttpServletRequest req) throws Exception {
    authenticate();
    AtomicBoolean chained = new AtomicBoolean();
    MockHttpServletResponse res = run(req, chained);
    assertEquals(400, res.getStatus());
    assertTrue(res.getContentType().startsWith("application/problem+json"));
    assertTrue(res.getContentAsString().contains("X-Workspace-Id basligi gecersiz."));
    assertTrue(!chained.get());
    assertNull(TenantContext.getWorkspaceId());
  }

  @Test
  void malformedHeadersGive400() throws Exception {
    UUID id = UUID.randomUUID();
    assertBadRequest(withHeaders("not-a-uuid"));
    assertBadRequest(withHeaders(""));
    assertBadRequest(withHeaders("a".repeat(2000)));
    assertBadRequest(withHeaders(id + "0"));
    assertBadRequest(withHeaders("1-1-1-1-1"));
    assertBadRequest(withHeaders(id.toString(), id.toString()));
    assertBadRequest(withHeaders(id.toString(), UUID.randomUUID().toString()));
  }

  @Test
  void uppercaseValidMemberHeaderPasses() throws Exception {
    UUID ws = UUID.randomUUID();
    when(membership.findRole(userId, ws)).thenReturn(Optional.of("MEMBER"));
    authenticate();
    AtomicBoolean chained = new AtomicBoolean();
    MockHttpServletResponse res = run(withHeaders(ws.toString().toUpperCase()), chained);
    assertEquals(200, res.getStatus());
    assertTrue(chained.get());
  }

  @Test
  void nonMemberGives403() throws Exception {
    when(membership.findRole(any(), any())).thenReturn(Optional.empty());
    authenticate();
    AtomicBoolean chained = new AtomicBoolean();
    MockHttpServletResponse res = run(withHeaders(UUID.randomUUID().toString()), chained);
    assertEquals(403, res.getStatus());
    assertTrue(!chained.get());
  }

  @Test
  void unauthenticatedBadHeaderIsIgnored() throws Exception {
    AtomicBoolean chained = new AtomicBoolean();
    MockHttpServletResponse res = run(withHeaders("not-a-uuid"), chained);
    assertEquals(200, res.getStatus());
    assertTrue(chained.get());
  }

  @Test
  void noHeaderPasses() throws Exception {
    authenticate();
    AtomicBoolean chained = new AtomicBoolean();
    assertEquals(200, run(withHeaders(), chained).getStatus());
    assertTrue(chained.get());
  }
}
