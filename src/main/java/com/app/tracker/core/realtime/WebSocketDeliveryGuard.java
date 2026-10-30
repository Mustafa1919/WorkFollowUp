package com.app.tracker.core.realtime;

import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * clientOutboundChannel interceptor'i: yetkiyi TESLIM ANINDA uygular. CONNECT (kimlik) ve SUBSCRIBE
 * (uyelik) kontrolleri tek seferlikti; acik bir baglanti token suresi dolsa, token iptal edilse
 * (logout / aile iptali -> {@code jwt:blacklist:<jti>}) ya da uyelik kalksa da olay almaya devam
 * ediyordu.
 *
 * <p>Neden iptal yayini (pub/sub) degil: o yol her iptal noktasinin (logout, aile iptali, parola
 * sifirlama, uye cikarma, rol degisikligi, workspace silme) bir olay yayinlamasini gerektirir; biri
 * unutulursa acik sessiz kalir ve pod'lar arasi koordinasyon gerekir. Teslim anindaki kontrol tum
 * iptal kaynaklarini yapisal olarak kapsar; oturum zaten tek pod'da yasadigi icin pod-yerel kayit
 * ({@link WebSocketSessionRegistry}) yeterlidir.
 *
 * <p>Yalniz MESSAGE frame'lerine bakilir. Sure kontrolu her mesajda bellekten. Blacklist ({@code
 * jwt:blacklist:<jti>}) HER MESAJDA Redis'e sorulur, onbellek YOK: logout bir kullanici eylemidir
 * ve aninda etki beklenir (HTTP de her istekte ayni sorguyu yapar); maliyet yayilim basina abone
 * sayisi kadar O(1) {@code EXISTS}'tir. Workspace uyeligi (DB) ise oturum ve workspace basina
 * {@link #REVALIDATE_INTERVAL}'de en cok bir kez sorgulanir (uye cikarma icin birkac sn gecikme
 * kabul; DB sorgusu Redis'ten pahalidir). Ihlalde orijinal mesaj yerine ayni oturuma STOMP ERROR
 * doner; Spring'in StompSubProtocolHandler'i ERROR frame'inden sonra oturumu {@code
 * CloseStatus.PROTOCOL_ERROR} ile kapatir. Kaydi olmayan oturumun mesaji dusurulur (fail-closed).
 */
@Component
public class WebSocketDeliveryGuard implements ChannelInterceptor {

  static final Duration REVALIDATE_INTERVAL = Duration.ofSeconds(5);
  static final String EXPIRED_MESSAGE = "Oturum suresi doldu";
  static final String REVOKED_MESSAGE = "Yetki geri alindi";

  private static final Logger log = LoggerFactory.getLogger(WebSocketDeliveryGuard.class);

  private final WebSocketSessionRegistry registry;
  private final Clock clock;
  private final Predicate<String> jtiRevoked;
  private final BiPredicate<UUID, UUID> isMember;

  @Autowired
  public WebSocketDeliveryGuard(
      WebSocketSessionRegistry registry,
      Clock clock,
      StringRedisTemplate redisTemplate,
      WorkspaceMembershipService membershipService) {
    this(
        registry,
        clock,
        jti -> Boolean.TRUE.equals(redisTemplate.hasKey("jwt:blacklist:" + jti)),
        (userId, workspaceId) -> membershipService.findRole(userId, workspaceId).isPresent());
  }

  WebSocketDeliveryGuard(
      WebSocketSessionRegistry registry,
      Clock clock,
      Predicate<String> jtiRevoked,
      BiPredicate<UUID, UUID> isMember) {
    this.registry = registry;
    this.clock = clock;
    this.jtiRevoked = jtiRevoked;
    this.isMember = isMember;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
      return message;
    }
    String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
    WebSocketSessionState state = registry.get(sessionId);
    if (state == null || state.isClosing()) {
      return null;
    }
    String violation;
    try {
      violation =
          check(
              state,
              SimpMessageHeaderAccessor.getDestination(message.getHeaders()),
              clock.instant());
    } catch (RuntimeException e) {
      // Redis/DB gecici hatasi (ya da beklenen uyelik sorgusunun hatasi): bu mesaj dusurulur
      // (fail-closed), oturum kapatilmaz; uyelik sahiplenmesi birakildigi icin sonraki mesajda
      // yeniden denenir.
      log.warn("WebSocket teslim denetimi yapilamadi, mesaj dusuruldu: session={}", sessionId, e);
      return null;
    }
    if (violation == null) {
      return message;
    }
    if (!state.markClosing()) {
      return null;
    }
    return errorFrame(sessionId, violation);
  }

  /** Ihlal yoksa {@code null}, varsa ERROR frame'inin {@code message} basligi. */
  private String check(WebSocketSessionState state, String destination, Instant now) {
    if (state.isExpired(now)) {
      return EXPIRED_MESSAGE;
    }
    if (jtiRevoked.test(state.jti())) {
      return REVOKED_MESSAGE;
    }
    if (destination == null) {
      return null;
    }
    Matcher matcher = WebSocketAuthInterceptor.PROJECT_TOPIC_PATTERN.matcher(destination);
    if (!matcher.matches()) {
      // Kullanici kuyrugu (/queue/..., Inbox): yalniz sure + blacklist.
      return null;
    }
    UUID workspaceId = UUID.fromString(matcher.group(1));
    boolean member =
        state.verifyMembership(
            workspaceId,
            now,
            REVALIDATE_INTERVAL,
            () -> isMember.test(state.userId(), workspaceId));
    return member ? null : REVOKED_MESSAGE;
  }

  private static Message<byte[]> errorFrame(String sessionId, String reason) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
    accessor.setMessage(reason);
    accessor.setSessionId(sessionId);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
