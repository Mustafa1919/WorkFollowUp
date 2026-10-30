package com.app.tracker.core.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StatementTimeoutConfigurationTest {
  @Test
  void configuresSessionTimeoutInMilliseconds() {
    try (HikariDataSource pool = new HikariDataSource()) {
      RoutingDataSourceConfig.configureStatementTimeout(pool, Duration.ofMillis(1250));
      assertEquals("SET statement_timeout = 1250", pool.getConnectionInitSql());
    }
  }

  @Test
  void preservesExistingSqlAndSeparatesTrailingComment() {
    try (HikariDataSource pool = new HikariDataSource()) {
      pool.setConnectionInitSql("SET application_name = 'tracker'; -- mevcut ayar");
      RoutingDataSourceConfig.configureStatementTimeout(pool, Duration.ofSeconds(30));
      assertEquals(
          "SET application_name = 'tracker'; -- mevcut ayar\n;\nSET statement_timeout = 30000",
          pool.getConnectionInitSql());
    }
  }

  @Test
  void disabledTimeoutDoesNotModifyInitialization() {
    try (HikariDataSource pool = new HikariDataSource()) {
      RoutingDataSourceConfig.configureStatementTimeout(pool, Duration.ZERO);
      assertNull(pool.getConnectionInitSql());
      pool.setConnectionInitSql("SELECT 1");
      RoutingDataSourceConfig.configureStatementTimeout(pool, Duration.ZERO);
      assertEquals("SELECT 1", pool.getConnectionInitSql());
    }
  }

  @Test
  void rejectsDurationsThatWouldDisableTimeoutAccidentally() {
    try (HikariDataSource pool = new HikariDataSource()) {
      assertThrows(
          IllegalArgumentException.class,
          () -> RoutingDataSourceConfig.configureStatementTimeout(pool, Duration.ofMillis(-1)));
      assertThrows(
          IllegalArgumentException.class,
          () -> RoutingDataSourceConfig.configureStatementTimeout(pool, Duration.ofNanos(1)));
    }
  }
}
