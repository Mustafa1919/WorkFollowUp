package com.app.tracker.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.comment.dto.CommentResponse;
import com.app.tracker.comment.model.Comment;
import com.app.tracker.comment.service.CommentService;
import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.notification.consumer.InboxNotificationConsumer;
import com.app.tracker.notification.model.Notification;
import com.app.tracker.notification.service.NotificationService;
import com.app.tracker.project.model.Project;
import com.app.tracker.project.service.ProjectService;
import com.app.tracker.task.model.Task;
import com.app.tracker.task.service.TaskService;
import com.app.tracker.task.service.TaskService.TaskDetail;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMemberService;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Urunlestirme Dalga 1.2 (V23): yorumlar + @mention. TaskAssignmentAndWatchIntegrationTest ile AYNI
 * iskelet — servis katmani + tenantExecutor.runAs, Inbox consumer'a elle zarf verilerek sinanir
 * (Kafka zamanlamasindan bagimsiz, deterministik).
 */
@SpringBootTest
class CommentIntegrationTest extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private WorkspaceMemberService memberService;
  @Autowired private ProjectService projectService;
  @Autowired private TaskService taskService;
  @Autowired private CommentService commentService;
  @Autowired private AuthService authService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private InboxNotificationConsumer consumer;
  @Autowired private NotificationService notificationService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private EntityManager entityManager;
  @Autowired private TransactionTemplate transactionTemplate;

  private UUID workspaceId;
  private Project project;
  private Task task;
  private UUID adminId;
  private UUID developerId;
  private UUID viewerId;

  @BeforeEach
  void setUp() {
    workspaceId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "Yorum WS"));
    project = inWorkspace(() -> projectService.createProject("CMT", "Yorum Projesi"));
    adminId = newMember(WorkspaceRole.ADMIN);
    developerId = newMember(WorkspaceRole.DEVELOPER);
    viewerId = newMember(WorkspaceRole.VIEWER);
    task = inWorkspace(() -> taskService.createTask(project.getId(), "Gorev", null, adminId));
  }

  // ---- CRUD + mention ayristirma

  @Test
  void authorBecomesWatcherAndCommentIsListedOldestFirst() {
    inWorkspace(() -> commentService.addComment(task.getId(), "ilk", developerId));
    inWorkspace(() -> commentService.addComment(task.getId(), "ikinci", developerId));

    TaskDetail detail = inWorkspace(() -> taskService.getDetail(task.getId()));
    assertTrue(detail.watcherIds().contains(developerId));

    List<Comment> page =
        inWorkspace(() -> commentService.listComments(task.getId(), 20, null)).data();
    assertEquals(List.of("ilk", "ikinci"), page.stream().map(Comment::getBody).toList());
  }

  @Test
  void mentionTokensAreValidatedAgainstMembershipAndSelfMentionIsIgnored() {
    // adminId gecerli uye, viewerId gecerli uye (VIEWER de mention edilebilir — izlemek rol
    // sinirsiz), UUID.randomUUID() uye DEGIL, developerId kendisi (aktor) mention ediyor.
    UUID nonMember = UUID.randomUUID();
    String body =
        "merhaba @["
            + adminId
            + "] @["
            + viewerId
            + "] @["
            + nonMember
            + "] @["
            + developerId
            + "]";
    Comment comment = inWorkspace(() -> commentService.addComment(task.getId(), body, developerId));

    TaskDetail detail = inWorkspace(() -> taskService.getDetail(task.getId()));
    assertTrue(detail.watcherIds().contains(adminId), "gecerli mention izleyici olur");
    assertTrue(detail.watcherIds().contains(viewerId), "VIEWER da mention edilebilir");
    assertFalse(detail.watcherIds().contains(nonMember), "uye olmayan mention izlenmez");

    assertEquals(body, comment.getBody());
  }

  @Test
  void blankOrTooLongBodyIsRejected() {
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> commentService.addComment(task.getId(), "   ", developerId)));
    String tooLong = "x".repeat(10001);
    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> commentService.addComment(task.getId(), tooLong, developerId)));
  }

  @Test
  void commentingOnDeletedTaskIsRejected() {
    inWorkspace(() -> taskService.deleteTask(task.getId(), adminId));
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            inWorkspace(() -> commentService.addComment(task.getId(), "gec kaldim", developerId)));
  }

  // ---- duzenleme

  @Test
  void onlyAuthorCanEditAndOnlyNewMentionsAreReAnnounced() {
    Comment created =
        inWorkspace(() -> commentService.addComment(task.getId(), "ilk hali", developerId));

    assertThrows(
        AccessDeniedException.class,
        () -> inWorkspace(() -> commentService.editComment(created.getId(), "baskasi", adminId)));

    Comment edited =
        inWorkspace(
            () ->
                commentService.editComment(
                    created.getId(), "artik @[" + adminId + "] var", developerId));
    assertEquals("artik " + "@[" + adminId + "] var", edited.getBody());
    assertTrue(edited.isEdited());

    TaskDetail detail = inWorkspace(() -> taskService.getDetail(task.getId()));
    assertTrue(detail.watcherIds().contains(adminId), "yeni mention izleyici olur");
  }

  @Test
  void editingDeletedCommentIsRejected() {
    Comment created =
        inWorkspace(() -> commentService.addComment(task.getId(), "silinecek", developerId));
    inWorkspace(() -> commentService.deleteComment(created.getId(), developerId));

    assertThrows(
        BusinessRuleException.class,
        () -> inWorkspace(() -> commentService.editComment(created.getId(), "yeni", developerId)));
  }

  // ---- silme

  @Test
  void authorOrAdminCanDeleteAndResponseMasksBody() {
    Comment byDeveloper =
        inWorkspace(() -> commentService.addComment(task.getId(), "dev yorumu", developerId));
    Comment byAdmin =
        inWorkspace(() -> commentService.addComment(task.getId(), "admin yorumu", adminId));

    // Yazan kendi yorumunu siler.
    inWorkspace(() -> commentService.deleteComment(byDeveloper.getId(), developerId));
    // ADMIN, BASKASININ yorumunu silebilir.
    inWorkspace(() -> commentService.deleteComment(byAdmin.getId(), adminId));
    // Idempotent: ikinci silme hata degil.
    inWorkspace(() -> commentService.deleteComment(byDeveloper.getId(), developerId));

    List<Comment> page =
        inWorkspace(() -> commentService.listComments(task.getId(), 20, null)).data();
    assertEquals(2, page.size(), "silinen satir listede KALIR");
    for (Comment comment : page) {
      CommentResponse response = CommentResponse.from(comment);
      assertEquals("[silindi]", response.body());
      assertTrue(response.deleted());
    }
  }

  @Test
  void nonAuthorNonAdminCannotDelete() {
    Comment comment =
        inWorkspace(() -> commentService.addComment(task.getId(), "yorum", developerId));
    UUID otherDeveloper = newMember(WorkspaceRole.DEVELOPER);
    assertThrows(
        AccessDeniedException.class,
        () -> inWorkspace(() -> commentService.deleteComment(comment.getId(), otherDeveloper)));
  }

  @Test
  void editAndDeleteEmitLiveRefreshEvents() {
    Comment comment =
        inWorkspace(() -> commentService.addComment(task.getId(), "ilk", developerId));
    inWorkspace(() -> commentService.editComment(comment.getId(), "ikinci", developerId));
    inWorkspace(() -> commentService.deleteComment(comment.getId(), developerId));
    // Idempotent ikinci silme yeni olay URETMEZ.
    inWorkspace(() -> commentService.deleteComment(comment.getId(), developerId));

    assertEquals(1, outboxCount("COMMENT_UPDATED"));
    assertEquals(1, outboxCount("COMMENT_DELETED"));
    assertEquals(0, outboxCount("COMMENT_MENTION"), "mention'siz duzenleme mention yayinlamaz");
  }

  private long outboxCount(String eventType) {
    Number count =
        transactionTemplate.execute(
            status ->
                (Number)
                    entityManager
                        .createNativeQuery(
                            "SELECT count(*) FROM outbox_events "
                                + "WHERE aggregate_id = ?1 AND event_type = ?2")
                        .setParameter(1, task.getId())
                        .setParameter(2, eventType)
                        .getSingleResult());
    return count.longValue();
  }

  // ---- yorum sayaci

  @Test
  void commentCountsIncludeDeletedRows() {
    Comment c1 = inWorkspace(() -> commentService.addComment(task.getId(), "a", developerId));
    inWorkspace(() -> commentService.addComment(task.getId(), "b", developerId));
    inWorkspace(() -> commentService.deleteComment(c1.getId(), developerId));

    Map<UUID, Integer> counts =
        inWorkspace(() -> commentService.commentCounts(List.of(task.getId())));
    assertEquals(2, counts.get(task.getId()));
  }

  // ---- Inbox alicilari (COMMENT_ADDED / COMMENT_MENTION)

  @Test
  void commentAddedNotifiesWatchersExceptActorAndExceptMentioned() {
    // admin olusturan (izleyici); viewer kendisi izlemeyi secti.
    inWorkspace(() -> taskService.watch(task.getId(), viewerId));

    consumer.onMessage(commentAddedEnvelope(developerId, List.of(viewerId)));

    // viewer hem izleyici hem mention edilmis -> yalniz COMMENT_MENTION'dan bildirim alir, burada
    // henuz COMMENT_MENTION olayi gonderilmedi, bu yuzden viewer'in COMMENT_ADDED'DEN bildirimi
    // OLMAMALI (mentionedUserIds ile disarida birakildi).
    assertTrue(
        forUser(viewerId).isEmpty(), "mention edilen COMMENT_ADDED'den ayrica bildirim almaz");
    // admin izleyici ve mention edilmemis -> genel bildirim alir.
    List<Notification> adminInbox = forUser(adminId);
    assertEquals(1, adminInbox.size());
    assertEquals("Yeni yorum eklendi.", adminInbox.get(0).getBody());
    // aktor (developer) kendi yorumundan bildirim almaz.
    assertTrue(forUser(developerId).isEmpty());
  }

  @Test
  void commentMentionNotifiesEvenNonWatchersAndActorIsExcluded() {
    // adminId burada izleyici DEGIL (task'i o olusturmadi -> developer olusturdu asagida).
    Task other =
        inWorkspace(() -> taskService.createTask(project.getId(), "Baska", null, developerId));

    consumer.onMessage(commentMentionEnvelope(other, developerId, List.of(adminId, developerId)));

    // developer hem aktor hem mention listesinde -> aktor oldugu icin HARIC.
    assertTrue(forUser(developerId).isEmpty());
    // admin izleyici olmasa bile (mention edildigi icin) bildirim alir.
    List<Notification> adminInbox = forUser(adminId);
    assertEquals(1, adminInbox.size());
    assertEquals("Yorumda sizden bahsedildi.", adminInbox.get(0).getBody());
  }

  @Test
  void endToEndAddCommentWithMentionProducesExactlyOneNotificationPerRecipient() {
    // Gercek servis cagrisi: outbox'a COMMENT_ADDED + COMMENT_MENTION yazilir (relay Kafka'ya
    // gonderir); burada relay'i beklemek yerine consumer'i DOGRUDAN, servisin urettigi payload'a
    // esdeger zarflarla besliyoruz (diger Inbox testleriyle ayni desen).
    inWorkspace(() -> taskService.watch(task.getId(), viewerId));
    String body = "bak @[" + adminId + "]";
    Comment comment = inWorkspace(() -> commentService.addComment(task.getId(), body, developerId));

    consumer.onMessage(
        envelopeFor("COMMENT_ADDED", comment.getId(), developerId, List.of(adminId)));
    consumer.onMessage(
        envelopeFor("COMMENT_MENTION", comment.getId(), developerId, List.of(adminId)));

    // admin TEK bir bildirim alir (mention'dan), COMMENT_ADDED'den ikinci bir tane ALMAZ.
    List<Notification> adminInbox = forUser(adminId);
    assertEquals(1, adminInbox.size());
    assertEquals("Yorumda sizden bahsedildi.", adminInbox.get(0).getBody());
    // viewer (izleyici, mention edilmemis) COMMENT_ADDED'den genel bildirim alir.
    List<Notification> viewerInbox = forUser(viewerId);
    assertEquals(1, viewerInbox.size());
    assertEquals("Yeni yorum eklendi.", viewerInbox.get(0).getBody());
  }

  // ---- yardimcilar

  private UUID newMember(String role) {
    String email = "cmt-" + UUID.randomUUID() + "@tracker.local";
    UUID userId = authService.register(email, PASSWORD, "Uye " + role).getId();
    membershipService.addMember(workspaceId, userId, role);
    return userId;
  }

  private List<Notification> forUser(UUID userId) {
    return inWorkspace(() -> notificationService.listMine(userId, 50, null, false).data());
  }

  private <T> T inWorkspace(Supplier<T> action) {
    return tenantExecutor.runAs(workspaceId, action);
  }

  private void inWorkspace(Runnable action) {
    tenantExecutor.runAs(workspaceId, action);
  }

  private String commentAddedEnvelope(UUID actorId, List<UUID> mentionedUserIds) {
    return envelopeFor("COMMENT_ADDED", UUID.randomUUID(), actorId, mentionedUserIds);
  }

  private String commentMentionEnvelope(Task forTask, UUID actorId, List<UUID> mentionedUserIds) {
    ObjectNode payload = basePayload(forTask, actorId);
    payload.put("commentId", UUID.randomUUID().toString());
    ArrayNode mentions = objectMapper.createArrayNode();
    mentionedUserIds.forEach(id -> mentions.add(id.toString()));
    payload.set("mentionedUserIds", mentions);
    return envelope("COMMENT_MENTION", payload);
  }

  private String envelopeFor(
      String eventType, UUID commentId, UUID actorId, List<UUID> mentionedUserIds) {
    ObjectNode payload = basePayload(task, actorId);
    payload.put("commentId", commentId.toString());
    ArrayNode mentions = objectMapper.createArrayNode();
    mentionedUserIds.forEach(id -> mentions.add(id.toString()));
    payload.set("mentionedUserIds", mentions);
    return envelope(eventType, payload);
  }

  private ObjectNode basePayload(Task forTask, UUID actorId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("taskId", forTask.getId().toString());
    payload.put("projectId", project.getId().toString());
    payload.put("actorId", actorId.toString());
    return payload;
  }

  private String envelope(String eventType, ObjectNode payload) {
    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("eventId", UUID.randomUUID().toString());
    envelope.put("eventType", eventType);
    envelope.put("schemaVersion", 1);
    envelope.put("workspaceId", workspaceId.toString());
    envelope.set("payload", payload);
    return objectMapper.writeValueAsString(envelope);
  }
}
