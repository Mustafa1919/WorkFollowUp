package com.app.tracker.core.realtime;

import com.app.tracker.core.security.CorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 4/5.3 — native WebSocket + STOMP (SockJS fallback yok, bu yuzden
 * Load Balancer'da sticky session GEREKMEZ, bkz. Bolum 5.3). In-memory Simple Broker (Secenek
 * A/broadcast consumer fan-out ile birlikte kullanilir — bkz. TaskEventBroadcastListener).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final WebSocketAuthInterceptor authInterceptor;
  private final CorsProperties corsProperties;

  public WebSocketConfig(WebSocketAuthInterceptor authInterceptor, CorsProperties corsProperties) {
    this.authInterceptor = authInterceptor;
    this.corsProperties = corsProperties;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint("/ws/connect")
        .setAllowedOriginPatterns(corsProperties.getAllowedOrigins().toArray(new String[0]));
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic");
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authInterceptor);
  }
}
