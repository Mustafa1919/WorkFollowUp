package com.app.tracker.core.realtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Pod-yerel {@code sessionId -> WebSocketSessionState} kaydi. Kaydi CONNECT dogrulamasi basarili
 * olunca {@link WebSocketAuthInterceptor} olusturur; {@link WebSocketDeliveryGuard} her MESSAGE
 * teslimatinda okur. Oturum zaten tek pod'da yasadigi icin pod'lar arasi paylasim GEREKMEZ.
 *
 * <p>Silme iki yerden: STOMP DISCONNECT frame'i (inbound interceptor) ve {@link
 * SessionDisconnectEvent} — transport koptugunda DISCONNECT frame'i gelmez, ama Spring bu olayi her
 * oturum sonunda yayinlar; ikisi birlikte kaydin sizmamasini saglar.
 */
@Component
public class WebSocketSessionRegistry {

  private final Map<String, WebSocketSessionState> sessions = new ConcurrentHashMap<>();

  void register(String sessionId, WebSocketSessionState state) {
    if (sessionId != null) {
      sessions.put(sessionId, state);
    }
  }

  WebSocketSessionState get(String sessionId) {
    return sessionId == null ? null : sessions.get(sessionId);
  }

  void remove(String sessionId) {
    if (sessionId != null) {
      sessions.remove(sessionId);
    }
  }

  int size() {
    return sessions.size();
  }

  @EventListener
  public void onSessionDisconnect(SessionDisconnectEvent event) {
    remove(event.getSessionId());
  }
}
