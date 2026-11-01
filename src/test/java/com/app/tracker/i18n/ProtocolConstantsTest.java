package com.app.tracker.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.tracker.core.exception.BusinessRuleException;
import com.app.tracker.core.exception.GlobalExceptionHandler;
import com.app.tracker.core.exception.ResourceNotFoundException;
import com.app.tracker.core.realtime.WebSocketAuthInterceptor;
import com.app.tracker.core.realtime.WebSocketDeliveryGuard;
import com.app.tracker.core.realtime.WebSocketSessionRegistry;
import com.app.tracker.core.security.DecodedJwt;
import com.app.tracker.core.security.JwtService;
import com.app.tracker.core.security.JwtValidationException;
import com.app.tracker.telemetry.TelemetryEventPublisher;
import com.app.tracker.workspace.service.WorkspaceMembershipService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ProtocolConstantsTest {
  @Test
  void R1_webSocketConstantsArePreservedExactly() throws Exception {
    // Reflection avoids javac inlining static final strings into the test class.
    var expired = WebSocketDeliveryGuard.class.getDeclaredField("EXPIRED_MESSAGE");
    var revoked = WebSocketDeliveryGuard.class.getDeclaredField("REVOKED_MESSAGE");
    expired.setAccessible(true);
    revoked.setAccessible(true);
    assertThat(expired.get(null)).isEqualTo("Oturum suresi doldu");
    assertThat(revoked.get(null)).isEqualTo("Yetki geri alindi");
  }

  @Test
  void R1_webSocketAuthenticationErrorIsPreservedExactly() {
    JwtService jwt = mock(JwtService.class);
    when(jwt.verify("invalid")).thenThrow(new JwtValidationException("test"));
    var interceptor =
        new WebSocketAuthInterceptor(
            jwt,
            mock(StringRedisTemplate.class),
            mock(WorkspaceMembershipService.class),
            mock(WebSocketSessionRegistry.class),
            Clock.systemUTC());
    var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
    headers.setNativeHeader("Authorization", "Bearer invalid");
    var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(MessagingException.class)
        .hasMessage("Kimlik dogrulama basarisiz.");
  }

  @Test
  void R1_validWebSocketAuthenticationIsAccepted() {
    JwtService jwt = mock(JwtService.class);
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(jwt.verify("valid"))
        .thenReturn(
            new DecodedJwt(userId, List.of(), "jti", Instant.parse("2099-01-01T00:00:00Z")));
    var interceptor =
        new WebSocketAuthInterceptor(
            jwt,
            mock(StringRedisTemplate.class),
            mock(WorkspaceMembershipService.class),
            mock(WebSocketSessionRegistry.class),
            Clock.systemUTC());
    var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
    headers.setSessionId("session-1");
    headers.setNativeHeader("Authorization", "Bearer valid");
    headers.setLeaveMutable(true);
    var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    assertThat(interceptor.preSend(message, null)).isSameAs(message);
    assertThat(headers.getUser().getName()).isEqualTo(userId.toString());
  }

  @Test
  void R2_businessRuleAndResourceProblemTypesAndStatusesArePreserved() {
    var handler = new GlobalExceptionHandler(mock(TelemetryEventPublisher.class));
    ProblemDetail rule = handler.handleBusinessRuleException(new BusinessRuleException("Örnek"));
    assertThat(rule.getStatus()).isEqualTo(400);
    assertThat(rule.getType().toString())
        .isEqualTo("https://api.app.com/errors/business-rule-violation");
    assertThat(rule.getTitle()).isEqualTo("İş Kuralı İhlali");
    ProblemDetail missing = handler.handleResourceNotFound(new ResourceNotFoundException("Yok"));
    assertThat(missing.getStatus()).isEqualTo(404);
    assertThat(missing.getType().toString()).isEqualTo("https://api.app.com/errors/not-found");
  }

  private static class Handler extends GlobalExceptionHandler {
    Handler() {
      super(mock(TelemetryEventPublisher.class));
    }

    ResponseEntity<Object> validation(BeanPropertyBindingResult errors) {
      return handleMethodArgumentNotValid(
          new MethodArgumentNotValidException(null, errors),
          HttpHeaders.EMPTY,
          HttpStatus.BAD_REQUEST,
          null);
    }
  }

  @Test
  void R2_validationFieldKeyIsNotTranslated() {
    var errors = new BeanPropertyBindingResult(new Object(), "request");
    errors.addError(
        new FieldError("request", "privacyNoticeAccepted", "Bu alanın onaylanması gerekiyor."));
    var response = new Handler().validation(errors);
    assertThat(response.getStatusCode().value()).isEqualTo(422);
    var problem = (ProblemDetail) response.getBody();
    assertThat(problem.getType().toString())
        .isEqualTo("https://api.app.com/errors/validation-failed");
    assertThat(problem.getProperties())
        .containsEntry(
            "invalid_params",
            List.of(
                Map.of(
                    "field",
                    "privacyNoticeAccepted",
                    "reason",
                    "Bu alanın onaylanması gerekiyor.")));
  }
}
