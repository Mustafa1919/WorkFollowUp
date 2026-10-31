package com.app.tracker.core.tenancy;

import com.app.tracker.workspace.service.WorkspaceMembershipService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
  private static final Pattern CANONICAL_UUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private final WorkspaceMembershipService membershipService;

  public WorkspaceContextFilter(WorkspaceMembershipService membershipService) {
    this.membershipService = membershipService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      List<String> values = Collections.list(request.getHeaders(WORKSPACE_HEADER));
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (!values.isEmpty() && authentication != null && authentication.isAuthenticated()) {
        if (values.size() != 1 || !CANONICAL_UUID.matcher(values.get(0)).matches()) {
          writeBadRequest(response);
          return;
        }
        UUID workspaceId = UUID.fromString(values.get(0));
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

  private static void writeBadRequest(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"type\":\"https://api.app.com/errors/bad-request\",\"title\":\"Gecersiz Istek\","
                + "\"status\":400,\"detail\":\"X-Workspace-Id basligi gecersiz.\"}");
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
