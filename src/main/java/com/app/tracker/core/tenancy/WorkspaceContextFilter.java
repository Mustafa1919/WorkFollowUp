package com.app.tracker.core.tenancy;

import com.app.tracker.workspace.service.WorkspaceMembershipService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>Uye olmayan istege 403 yaniti DOGRUDAN yazilir, {@code sendError} KULLANILMAZ: sendError
 * container'da {@code /error}'a ikinci bir dispatch baslatir; orada JWT kimligi yoktur ve
 * SecurityConfig'in 401 entry point'i yaniti 401'e cevirirdi — frontend 401'de refresh deneyip
 * oturumu kapatir (MockMvc error dispatch yapmadigi icin HTTP testleri bunu yakalamaz).
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
          writeForbidden(response);
          return;
        }
        TenantContext.setWorkspaceId(workspaceId);
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  private static void writeForbidden(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"type\":\"https://api.app.com/errors/forbidden\",\"title\":\"Erisim Reddedildi\","
                + "\"status\":403,\"detail\":\"Bu workspace'e uye degilsiniz.\"}");
  }
}
