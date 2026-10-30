package com.app.tracker.security.support;

import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.user.model.PrivacyNotice;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContext;

/**
 * Guvenlik dilimleri (D3-D7) icin ortak iki kiracili fikstur. Spring bean'i DEGILDIR: testin
 * ApplicationContext'inden servisleri alan duz siniftir. Her {@link #create} cagrisi yeni,
 * benzersiz kullanici/workspace/proje/gorev uretir (testler arasi DB/Redis sizintisi olmaz).
 *
 * <p>Rol anahtarlari {@link WorkspaceRole} sabitleridir ({@code WorkspaceRole.ADMIN} = {@code
 * WORKSPACE_ADMIN} vb.); {@code WorkspaceRole} bir enum degil String sabit sinifi oldugu icin map
 * anahtari {@code String}'dir.
 */
public final class SecurityFixture {

  /** Fikstur kullanicilarinin ortak parolasi (login testleri icin). */
  public static final String PASSWORD = "correct-horse-battery";

  public record Actor(UUID userId, String email, String accessToken) {}

  /**
   * Bir kiraci: workspace, 4 rolde uye, bir proje, DEVELOPER'a atanmis bir gorev ({@code taskId},
   * {@code taskKey} = {@code KEY-NO}) ve atanmamis bir baska gorev ({@code otherTaskId}).
   */
  public record Tenant(
      UUID workspaceId,
      Map<String, Actor> actors,
      UUID projectId,
      UUID taskId,
      String taskKey,
      UUID otherTaskId) {

    public Actor admin() {
      return actors.get(WorkspaceRole.ADMIN);
    }

    public Actor manager() {
      return actors.get(WorkspaceRole.MANAGER);
    }

    public Actor developer() {
      return actors.get(WorkspaceRole.DEVELOPER);
    }

    public Actor viewer() {
      return actors.get(WorkspaceRole.VIEWER);
    }
  }

  private final AuthService authService;
  private final WorkspaceService workspaceService;
  private final WorkspaceMembershipService membershipService;
  private final TenantExecutor tenantExecutor;
  private final ProjectService projectService;
  private final TaskService taskService;
  private final SecurityHttp http;

  private Tenant tenantA;
  private Tenant tenantB;
  private Actor outsider;

  private SecurityFixture(ApplicationContext ctx, int port) {
    this.authService = ctx.getBean(AuthService.class);
    this.workspaceService = ctx.getBean(WorkspaceService.class);
    this.membershipService = ctx.getBean(WorkspaceMembershipService.class);
    this.tenantExecutor = ctx.getBean(TenantExecutor.class);
    this.projectService = ctx.getBean(ProjectService.class);
    this.taskService = ctx.getBean(TaskService.class);
    this.http = new SecurityHttp(port);
  }

  public static SecurityFixture create(ApplicationContext ctx, int port) {
    SecurityFixture fixture = new SecurityFixture(ctx, port);
    fixture.tenantA = fixture.buildTenant("A");
    fixture.tenantB = fixture.buildTenant("B");
    fixture.outsider = fixture.newActor("outsider");
    return fixture;
  }

  public Tenant a() {
    return tenantA;
  }

  public Tenant b() {
    return tenantB;
  }

  /** Hicbir workspace'in uyesi olmayan, gecerli oturumlu kullanici. */
  public Actor outsider() {
    return outsider;
  }

  public SecurityHttp http() {
    return http;
  }

  /**
   * D4: kiraciya yeni (benzersiz, oturumlu) bir uye ekler. Mevcut fikstur uyelerine dokunmadan
   * silinebilir/rolu degistirilebilir kayit gereken testler icin. {@code tag} e-postaya ve ada
   * girer ({@code sec-<tag>-<uuid>@tracker.local}).
   */
  public Actor addMember(Tenant tenant, String role, String tag) {
    Actor actor = newActor(tag);
    membershipService.addMember(tenant.workspaceId(), actor.userId(), role);
    return actor;
  }

  private Tenant buildTenant(String label) {
    UUID workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(
        null, () -> workspaceService.createWorkspace(workspaceId, "Sec WS " + label));

    Map<String, Actor> actors = new LinkedHashMap<>();
    for (String role :
        List.of(
            WorkspaceRole.ADMIN,
            WorkspaceRole.MANAGER,
            WorkspaceRole.DEVELOPER,
            WorkspaceRole.VIEWER)) {
      Actor actor = newActor("fx-" + label.toLowerCase() + "-" + role.toLowerCase());
      membershipService.addMember(workspaceId, actor.userId(), role);
      actors.put(role, actor);
    }
    Actor admin = actors.get(WorkspaceRole.ADMIN);
    Actor developer = actors.get(WorkspaceRole.DEVELOPER);

    Project project =
        tenantExecutor.runAs(
            workspaceId, () -> projectService.createProject("SEC", "Sec Project " + label));
    Task assigned =
        tenantExecutor.runAs(
            workspaceId,
            () -> taskService.createTask(project.getId(), "Assigned task " + label, null, null));
    tenantExecutor.runAs(
        workspaceId,
        () -> taskService.assign(assigned.getId(), developer.userId(), admin.userId()));
    Task other =
        tenantExecutor.runAs(
            workspaceId,
            () -> taskService.createTask(project.getId(), "Unassigned task " + label, null, null));

    return new Tenant(
        workspaceId,
        Map.copyOf(actors),
        project.getId(),
        assigned.getId(),
        project.getKey() + "-" + assigned.getTaskNumber(),
        other.getId());
  }

  private Actor newActor(String tag) {
    String email = "sec-" + tag + "-" + UUID.randomUUID() + "@tracker.local";
    UUID userId =
        authService.register(email, PASSWORD, "Sec " + tag, PrivacyNotice.CURRENT_VERSION).getId();
    String accessToken = authService.login(email, PASSWORD, "127.0.0.1").accessToken();
    return new Actor(userId, email, accessToken);
  }
}
