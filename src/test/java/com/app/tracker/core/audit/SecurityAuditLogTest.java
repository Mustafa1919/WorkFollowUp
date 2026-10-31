package com.app.tracker.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SecurityAuditLogTest {

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger("security.audit");

  @BeforeEach
  void setUp() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  @Test
  void writesSortedAttributesAndDashForMissingActor() {
    new SecurityAuditLog()
        .record(SecurityAuditEvent.LOGIN_FAILED, null, Map.of("reason", "locked", "ip", "1.2.3.4"));

    assertThat(appender.list).hasSize(1);
    assertThat(appender.list.get(0).getFormattedMessage())
        .isEqualTo("security_audit event=LOGIN_FAILED actor=- ip=1.2.3.4 reason=locked");
  }

  @Test
  void sanitizesControlCharactersAndTruncates() {
    UUID actor = UUID.randomUUID();
    new SecurityAuditLog()
        .record(
            SecurityAuditEvent.ACCESS_DENIED,
            actor,
            Map.of("path", "/a b\r\nevent=X", "long", "x".repeat(150)));

    String line = appender.list.get(0).getFormattedMessage();
    assertThat(line).doesNotContain("\n").doesNotContain("\r");
    assertThat(line).contains("path=/a_b__event_X");
    assertThat(line).contains("actor=" + actor);
    assertThat(line).contains("long=" + "x".repeat(100) + " ");
  }
}
