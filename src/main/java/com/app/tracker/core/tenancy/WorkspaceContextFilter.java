package com.app.tracker.core.tenancy;

import com.app.tracker.workspace.service.WorkspaceMembershipService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Istekteki {@code X-Workspace-Id} header'ini {@code workspace_users} uzerinden dogrular (RLS'ten
 * ONCE, workspace_users RLS'e tabi degildir) ve gecerliyse {@link TenantContext}'i kurar. Doc'ta
 * workspaceId JWT payload'ina KONMAZ (bkz. JwtService) — bu yuzden secim her istekte bu header ile
 * yapilir. Thread pool'da context sizmasini onlemek icin istek sonunda HER ZAMAN temizlenir.
 */
@Component
public class WorkspaceContextFilter extends OncePerRequestFilter {

  private static final String WORKSPACE_HEADER = "X-Workspace-Id";

  private final WorkspaceMembershipService membershipService;

  public WorkspaceContextFilter(WorkspaceMembershipService membershipService) {
    this.membershipService = membershipService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String header = request.getHeader(WORKSPACE_HEADER);
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (header != null && authentication != null && authentication.isAuthenticated()) {
        UUID workspaceId = UUID.fromString(header);
        UUID userId = (UUID) authentication.getPrincipal();
        if (membershipService.findRole(userId, workspaceId).isEmpty()) {
          response.sendError(HttpServletResponse.SC_FORBIDDEN, "Bu workspace'e uye degilsiniz.");
          return;
        }
        TenantContext.setWorkspaceId(workspaceId);
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }
}
