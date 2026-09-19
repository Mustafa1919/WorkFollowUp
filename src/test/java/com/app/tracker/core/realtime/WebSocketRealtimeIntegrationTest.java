package com.app.tracker.core.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.security.AuthService;
import com.app.tracker.core.security.DecodedJwt;
import com.app.tracker.core.security.JwtService;
import com.app.tracker.core.tenancy.TenantExecutor;
import com.app.tracker.workspace.model.WorkspaceRole;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import com.app.tracker.workspace.service.WorkspaceService;
import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Ilerleme.md'nin "bilinen kalan boslugu" kapatir: {@code WebSocketAuthInterceptor} (CONNECT kimlik
 * dogrulama + SUBSCRIBE yetkilendirme) ve {@code TaskEventBroadcastListener} (Kafka'dan STOMP'a
 * fan-out) daha once hic entegrasyon testi gormemisti. Gercek bir STOMP istemcisiyle (embedded
 * Tomcat + gercek Kafka container) uctan uca dogrulanir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketRealtimeIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private AuthService authService;
  @Autowired private JwtService jwtService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceMembershipService membershipService;
  @Autowired private TenantExecutor tenantExecutor;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;

  private String wsUrl() {
    return "ws://localhost:" + port + "/ws/connect";
  }

  private WebSocketStompClient newStompClient() {
    // Varsayilan SimpleMessageConverter sadece zaten hedef tipe uyan payload'lari gecirir; frame
    // govdesi byte[] olarak gelir ve String.class'a hicbir zaman donusturulmez (handleFrame hic
    // cagrilmaz). StringMessageConverter bu donusumu gercekten yapar.
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new StringMessageConverter());
    return client;
  }

  private String issueTokenForNewUser(String email) {
    authService.register(email, "correct-horse-battery", "Test User");
    return authService.login(email, "correct-horse-battery", "127.0.0.1").accessToken();
  }

  @Test
  void connectWithoutAuthorizationHeaderIsRejected() {
    WebSocketStompClient client = newStompClient();
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.setHeartbeat(new long[] {0, 0});

    CompletableFuture<StompSession> future =
        client.connectAsync(
            wsUrl(),
            (WebSocketHttpHeaders) null,
            connectHeaders,
            new StompSessionHandlerAdapter() {});

    assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
  }

  @Test
  void connectWithInvalidTokenIsRejected() {
    WebSocketStompClient client = newStompClient();
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.setHeartbeat(new long[] {0, 0});
    connectHeaders.add("Authorization", "Bearer not-a-real-jwt");

    CompletableFuture<StompSession> future =
        client.connectAsync(
            wsUrl(),
            (WebSocketHttpHeaders) null,
            connectHeaders,
            new StompSessionHandlerAdapter() {});

    assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
  }

  /**
   * TaskEventBroadcastListener {@code auto.offset.reset=latest} ile calisir; "task.events" topic'i
   * bu JVM'de ilk kez olusturuluyorsa consumer'in partition assignment'i tamamlamasi ile topic'in
   * fiilen var olmasi arasinda bir yaris durumu vardir (ilk mesaj "latest"in gerisinde kalip
   * atlanabilir). Once atilacak, testin kendisiyle ilgisiz bir "isinma" mesaji bu yarisi kalici
   * olarak kapatir: topic'i yaratir ve consumer'in en az bir rebalance/poll dongusu tamamlamasi
   * icin zaman tanir.
   */
  private void warmUpBroadcastListener() {
    kafkaTemplate.send("task.events", "warmup-" + UUID.randomUUID(), "{}");
    try {
      Thread.sleep(3000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void memberCanSubscribeAndReceivesKafkaFanOutEvent() throws Exception {
    warmUpBroadcastListener();
    String token = issueTokenForNewUser("ws-member@tracker.local");
    DecodedJwt decoded = jwtService.verify(token);

    UUID workspaceId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "WS Realtime"));
    membershipService.addMember(workspaceId, decoded.userId(), WorkspaceRole.DEVELOPER);

    WebSocketStompClient client = newStompClient();
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.setHeartbeat(new long[] {0, 0});
    connectHeaders.add("Authorization", "Bearer " + token);

    StompSession session =
        client
            .connectAsync(
                wsUrl(),
                (WebSocketHttpHeaders) null,
                connectHeaders,
                new StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS);

    LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    String destination = "/topic/workspace.%s.project.%s".formatted(workspaceId, projectId);
    session.subscribe(
        destination,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return String.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, Object payload) {
            received.offer((String) payload);
          }
        });

    // SUBSCRIBE frame'inin sunucuda islenip abonelik kaydinin tamamlanmasi icin kisa bir bekleme;
    // STOMP protokolunde SUBSCRIBE'a senkron bir "onay" yanit yok.
    Thread.sleep(500);

    String envelope =
        "{\"eventId\":\"%s\",\"eventType\":\"TASK_CREATED\",\"workspaceId\":\"%s\",\"payload\":{\"projectId\":\"%s\",\"taskId\":\"%s\"}}"
            .formatted(UUID.randomUUID(), workspaceId, projectId, UUID.randomUUID());
    kafkaTemplate.send("task.events", UUID.randomUUID().toString(), envelope);

    String message = received.poll(15, TimeUnit.SECONDS);
    assertNotNull(message, "15 saniye icinde fan-out mesaji STOMP istemcisine ulasmadi");
    assertEquals(envelope, message);

    session.disconnect();
  }

  @Test
  void nonMemberCannotSubscribeToWorkspaceChannel() throws Exception {
    String token = issueTokenForNewUser("ws-outsider@tracker.local");
    UUID workspaceId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    tenantExecutor.runAs(null, () -> workspaceService.createWorkspace(workspaceId, "WS Outsider"));
    // Bilerek uye eklenmedi.

    WebSocketStompClient client = newStompClient();
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.setHeartbeat(new long[] {0, 0});
    connectHeaders.add("Authorization", "Bearer " + token);

    CompletableFuture<Throwable> subscriptionError = new CompletableFuture<>();
    StompSession session =
        client
            .connectAsync(
                wsUrl(),
                (WebSocketHttpHeaders) null,
                connectHeaders,
                new StompSessionHandlerAdapter() {
                  @Override
                  public void handleException(
                      StompSession session,
                      StompCommand command,
                      StompHeaders headers,
                      byte[] payload,
                      Throwable exception) {
                    subscriptionError.complete(exception);
                  }

                  @Override
                  public void handleTransportError(StompSession session, Throwable exception) {
                    subscriptionError.complete(exception);
                  }
                })
            .get(10, TimeUnit.SECONDS);

    String destination = "/topic/workspace.%s.project.%s".formatted(workspaceId, projectId);
    session.subscribe(destination, new StompSessionHandlerAdapter() {});

    Throwable error = subscriptionError.get(10, TimeUnit.SECONDS);
    assertNotNull(error);
  }
}
