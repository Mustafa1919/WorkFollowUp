package com.app.tracker.core.realtime;

import com.app.tracker.core.security.DecodedJwt;
import com.app.tracker.core.security.JwtService;
import com.app.tracker.core.security.JwtValidationException;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 4 — {@code CONNECT} frame'inde {@code Authorization} STOMP
 * header'i (native HTTP header DEGIL — WebSocket handshake'inde custom header koymak tarayicidan
 * mumkun degildir, STOMP frame header'i kullanilir) JwtService ile dogrulanir;
 * JwtAuthenticationFilter ile ayni kurallar (imza/sure + Redis blacklist) uygulanir.
 *
 * <p>{@code SUBSCRIBE} frame'inde, hedef kanaldaki {@code workspaceId}'ye CONNECT'te kimligi
 * dogrulanmis kullanicinin ({@code workspace_users} uzerinden, RLS'ten ONCE — SecurityGuard ayni
 * gerekceyle bunu yapiyor) uyeligi kontrol edilir; aksi halde herhangi bir kimlik dogrulanmis
 * kullanici baska bir workspace'in kanalina abone olabilirdi (IDOR).
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

  private static final Pattern PROJECT_TOPIC_PATTERN =
      Pattern.compile("^/topic/workspace\\.([0-9a-fA-F-]{36})\\.project\\.([0-9a-fA-F-]{36})$");

  private final JwtService jwtService;
  private final StringRedisTemplate redisTemplate;
  private final WorkspaceMembershipService membershipService;

  public WebSocketAuthInterceptor(
      JwtService jwtService,
      StringRedisTemplate redisTemplate,
      WorkspaceMembershipService membershipService) {
    this.jwtService = jwtService;
    this.redisTemplate = redisTemplate;
    this.membershipService = membershipService;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null) {
      return message;
    }
    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
      authenticate(accessor);
    } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
      authorizeSubscription(accessor);
    }
    return message;
  }

  private void authenticate(StompHeaderAccessor accessor) {
    String header = accessor.getFirstNativeHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      throw new MessagingException("Authorization header eksik.");
    }
    try {
      DecodedJwt decoded = jwtService.verify(header.substring("Bearer ".length()));
      if (Boolean.TRUE.equals(redisTemplate.hasKey("jwt:blacklist:" + decoded.jti()))) {
        throw new JwtValidationException("Token iptal edilmis.");
      }
      accessor.setUser(new WebSocketPrincipal(decoded.userId()));
    } catch (JwtValidationException e) {
      throw new MessagingException("Kimlik dogrulama basarisiz.", e);
    }
  }

  private void authorizeSubscription(StompHeaderAccessor accessor) {
    String destination = accessor.getDestination();
    if (destination == null) {
      throw new MessagingException("Hedef kanal belirtilmemis.");
    }
    Matcher matcher = PROJECT_TOPIC_PATTERN.matcher(destination);
    if (!matcher.matches()) {
      throw new MessagingException("Gecersiz kanal: " + destination);
    }
    UUID workspaceId = UUID.fromString(matcher.group(1));
    WebSocketPrincipal principal = (WebSocketPrincipal) accessor.getUser();
    if (principal == null) {
      throw new MessagingException("Once kimlik dogrulanmali.");
    }
    if (membershipService.findRole(principal.userId(), workspaceId).isEmpty()) {
      throw new MessagingException("Bu workspace'e uye degilsiniz: " + workspaceId);
    }
  }
}
