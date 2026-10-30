package com.app.tracker.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.datasource.ReadReplica;
import com.app.tracker.core.datasource.ReadReplicaProperties;
import com.app.tracker.core.datasource.RoutingDataSourceConfig;
import com.app.tracker.core.realtime.WebSocketAuthInterceptor;
import com.app.tracker.core.realtime.WebSocketConfig;
import com.app.tracker.core.realtime.WebSocketDeliveryGuard;
import com.app.tracker.core.security.CorsProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/**
 * Dilim 05. Varsayilan kaynaklari yerel Maven jar'laridir: spring-boot-tomcat/4.1.2
 * TomcatServerProperties ve Threads kuruculari; HikariCP/7.0.2 HikariConfig.validate
 * (DEFAULT_POOL_SIZE=10); spring-websocket/7.0.10 StompSubProtocolHandler ve
 * SubProtocolWebSocketHandler kuruculari. Karsilastirma uygulama sabitlerinden degil bu siniflarin
 * yeni orneklerinden yapilir.
 */
@NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
class StatementTimeoutContractTest {

  @Test
  void R5_bothConfiguredPoolsInitializeDefaultThirtySecondSessionTimeout() throws Exception {
    try (Pools pools = pools(yaml())) {
      assertEquals(2, pools.list.size());
      for (HikariDataSource pool : pools.list) {
        assertNotNull(pool.getConnectionInitSql());
        assertTrue(pool.getConnectionInitSql().contains("SET statement_timeout = 30000"));
        assertFalse(pool.getConnectionInitSql().contains("SET LOCAL"));
      }
    }
  }

  @Test
  void R5_existingInitializationIncludingTrailingCommentIsPreservedInBothPools() throws Exception {
    MockEnvironment environment =
        yaml()
            .withProperty(
                "spring.datasource.hikari.connection-init-sql",
                "SET application_name = 'contract-init'; -- onceki SQL");
    try (Pools pools = pools(environment)) {
      for (HikariDataSource pool : pools.list) {
        String sql = pool.getConnectionInitSql();
        assertTrue(sql.startsWith("SET application_name = 'contract-init'; -- onceki SQL"));
        assertTrue(
            sql.substring(sql.indexOf("--")).contains("\n"), "zaman asimi yorum icinde kaldi");
        assertTrue(sql.contains("SET statement_timeout = 30000"));
      }
    }
  }

  @Test
  void K6_zeroDoesNotSetTimeoutOrReplaceExistingInitializationInEitherPool() throws Exception {
    MockEnvironment environment = yaml().withProperty("app.datasource.statement-timeout", "0");
    try (Pools pools = pools(environment)) {
      pools.list.forEach(pool -> assertNull(pool.getConnectionInitSql()));
    }
    environment.withProperty("spring.datasource.hikari.connection-init-sql", "SELECT 42");
    try (Pools pools = pools(environment)) {
      pools.list.forEach(pool -> assertEquals("SELECT 42", pool.getConnectionInitSql()));
    }
  }

  @Test
  void K5_migrateProfileUsesBootPoolWithoutApplicationTimeout() {
    new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("migrate"))
        .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
        .withUserConfiguration(RoutingDataSourceConfig.class)
        .withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://localhost:1/contract",
            "app.datasource.statement-timeout=1s")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(context.getBeansOfType(RoutingDataSourceConfig.class).isEmpty());
              assertNull(context.getBean(HikariDataSource.class).getConnectionInitSql());
            });
  }

  @Test
  void R7_explicitYamlBudgetsEqualIndependentBootAndHikariJarDefaults() throws Exception {
    MockEnvironment environment = yaml();
    for (String key :
        List.of(
            "spring.datasource.hikari.maximum-pool-size",
            "app.datasource.read.maximum-pool-size",
            "server.tomcat.threads.max",
            "server.tomcat.max-connections",
            "server.tomcat.accept-count")) {
      assertNotNull(environment.getProperty(key), "acik ayar eksik: " + key);
    }
    TomcatServerProperties defaults = new TomcatServerProperties();
    TomcatServerProperties configured =
        Binder.get(environment)
            .bind("server.tomcat", TomcatServerProperties.class)
            .orElseThrow(AssertionError::new);
    assertEquals(defaults.getThreads().getMax(), configured.getThreads().getMax());
    assertEquals(defaults.getMaxConnections(), configured.getMaxConnections());
    assertEquals(defaults.getAcceptCount(), configured.getAcceptCount());
    HikariConfig hikariDefaults = new HikariConfig();
    hikariDefaults.setJdbcUrl("jdbc:postgresql://localhost:1/contract");
    hikariDefaults.validate();
    assertEquals(10, hikariDefaults.getMaximumPoolSize());
    try (Pools pools = pools(environment)) {
      for (HikariDataSource pool : pools.list)
        assertEquals(hikariDefaults.getMaximumPoolSize(), pool.getMaximumPoolSize());
    }
  }

  @Test
  void R8_K8_allThreeTransportLimitsAreExplicitAndEqualIndependentSpringJarDefaults() {
    WebSocketTransportRegistration registration = new WebSocketTransportRegistration();
    new WebSocketConfig(
            mock(WebSocketAuthInterceptor.class),
            mock(WebSocketDeliveryGuard.class),
            new CorsProperties())
        .configureWebSocketTransport(registration);
    StompSubProtocolHandler stomp = new StompSubProtocolHandler();
    SubProtocolWebSocketHandler handler =
        new SubProtocolWebSocketHandler(
            new ExecutorSubscribableChannel(), new ExecutorSubscribableChannel());
    assertEquals(
        stomp.getMessageSizeLimit(),
        (Integer) ReflectionTestUtils.invokeMethod(registration, "getMessageSizeLimit"));
    assertEquals(
        handler.getSendBufferSizeLimit(),
        (Integer) ReflectionTestUtils.invokeMethod(registration, "getSendBufferSizeLimit"));
    assertEquals(
        handler.getSendTimeLimit(),
        (Integer) ReflectionTestUtils.invokeMethod(registration, "getSendTimeLimit"));
  }

  private static MockEnvironment yaml() throws Exception {
    MockEnvironment environment = new MockEnvironment();
    new YamlPropertySourceLoader()
        .load("contract-yaml", new ClassPathResource("application.yml"))
        .forEach(source -> environment.getPropertySources().addLast(source));
    return environment;
  }

  @SuppressWarnings("unchecked")
  private static Pools pools(MockEnvironment environment) {
    DataSourceProperties properties = new DataSourceProperties();
    properties.setUrl("jdbc:postgresql://localhost:1/contract");
    properties.setUsername("contract");
    DataSource dataSource =
        new RoutingDataSourceConfig()
            .dataSource(
                properties,
                new ReadReplicaProperties(
                    "jdbc:postgresql://localhost:1/read",
                    null,
                    null,
                    environment.getProperty(
                        "app.datasource.read.maximum-pool-size", Integer.class)),
                environment);
    return new Pools(
        (AutoCloseable) dataSource,
        (List<HikariDataSource>) ReflectionTestUtils.getField(dataSource, "pools"));
  }

  private record Pools(AutoCloseable dataSource, List<HikariDataSource> list)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      dataSource.close();
    }
  }

  @Nested
  @Tag("docker")
  @SpringBootTest
  @Import(ProbeConfiguration.class)
  class DefaultTimeout extends DatabaseHarness {
    @Test
    void R5_bothApplicationPoolsReportExactlyThirtySeconds() throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        assertEquals("30s", scalar(connection, "SHOW statement_timeout"));
      }
      assertEquals(List.of(READ_POOL_APPLICATION_NAME, "30s"), probe.readSettings());
    }

    @Test
    void R5_K5_setLocalWorkspaceDoesNotChangeTimeoutOnSameConnectionAfterCommitOrRollback()
        throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        String pid = scalar(connection, "SELECT pg_backend_pid()");
        assertEquals("30s", scalar(connection, "SHOW statement_timeout"));
        connection.setAutoCommit(false);
        String workspace = UUID.randomUUID().toString();
        scalar(
            connection, "SELECT set_config('app.current_workspace_id', '" + workspace + "', true)");
        assertEquals(
            workspace, scalar(connection, "SELECT current_setting('app.current_workspace_id')"));
        assertEquals("30s", scalar(connection, "SHOW statement_timeout"));
        connection.commit();
        assertEquals(pid, scalar(connection, "SELECT pg_backend_pid()"));
        assertEquals("30s", scalar(connection, "SHOW statement_timeout"));
        scalar(
            connection, "SELECT set_config('app.current_workspace_id', '" + workspace + "', true)");
        connection.rollback();
        assertEquals(pid, scalar(connection, "SELECT pg_backend_pid()"));
        assertEquals("30s", scalar(connection, "SHOW statement_timeout"));
        connection.setAutoCommit(true);
      }
      assertEquals(
          List.of(READ_POOL_APPLICATION_NAME, "30s"), probe.readSettingsAfterLocalWorkspace());
    }

    @Test
    void R7_runningApplicationRetainsDefaultPoolSizesAndTomcatBudgets() throws Exception {
      TomcatServerProperties defaults = new TomcatServerProperties();
      assertEquals(defaults.getThreads().getMax(), tomcat.getThreads().getMax());
      assertEquals(defaults.getMaxConnections(), tomcat.getMaxConnections());
      assertEquals(defaults.getAcceptCount(), tomcat.getAcceptCount());
      @SuppressWarnings("unchecked")
      List<HikariDataSource> runningPools =
          (List<HikariDataSource>) ReflectionTestUtils.getField(dataSource, "pools");
      assertNotNull(runningPools);
      assertEquals(2, runningPools.size());
      runningPools.forEach(pool -> assertEquals(10, pool.getMaximumPoolSize()));
    }
  }

  @Nested
  @Tag("docker")
  @SpringBootTest(
      properties = {
        "app.datasource.statement-timeout=1s",
        "spring.datasource.hikari.connection-init-sql=SET application_name = 'contract-init'; -- korunacak"
      })
  @Import(ProbeConfiguration.class)
  class ShortTimeout extends DatabaseHarness {
    @Test
    void R5_K5_shortQueriesSucceedLongQueriesCancelWith57014InBothPools() throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        assertEquals("1s", scalar(connection, "SHOW statement_timeout"));
        assertEquals("contract-init", scalar(connection, "SHOW application_name"));
        assertEquals("42", scalar(connection, "SELECT 42"));
        SQLException failure =
            assertThrows(SQLException.class, () -> scalar(connection, "SELECT pg_sleep(2)"));
        assertEquals("57014", failure.getSQLState());
        assertEquals("42", scalar(connection, "SELECT 42"));
      }
      assertEquals("57014", probe.readCancellation());
    }
  }

  @Nested
  @Tag("docker")
  @SpringBootTest(properties = "app.datasource.statement-timeout=0")
  @Import(ProbeConfiguration.class)
  class DisabledTimeout extends DatabaseHarness {
    @Test
    void K6_R5_zeroLeavesPostgresDefaultAndShortQueryWorksInBothPools() throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        assertEquals("0", scalar(connection, "SHOW statement_timeout"));
        assertEquals("42", scalar(connection, "SELECT 42"));
      }
      assertEquals(List.of(READ_POOL_APPLICATION_NAME, "0"), probe.readSettings());
    }
  }

  abstract static class DatabaseHarness extends AbstractIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired Probe probe;
    @Autowired TomcatServerProperties tomcat;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfiguration {
    @Bean
    Probe timeoutContractProbe(DataSource dataSource) {
      return new Probe(dataSource);
    }
  }

  public static class Probe {
    private final DataSource dataSource;

    Probe(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    @ReadReplica
    @Transactional(readOnly = true)
    public List<String> readSettings() throws SQLException {
      try (Connection connection = dataSource.getConnection()) {
        return List.of(
            scalar(connection, "SHOW application_name"),
            scalar(connection, "SHOW statement_timeout"));
      }
    }

    @ReadReplica
    @Transactional(readOnly = true)
    public List<String> readSettingsAfterLocalWorkspace() throws SQLException {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(false);
        scalar(
            connection,
            "SELECT set_config('app.current_workspace_id', '" + UUID.randomUUID() + "', true)");
        assertEquals("30s", scalar(connection, "SHOW statement_timeout"));
        connection.commit();
        return List.of(
            scalar(connection, "SHOW application_name"),
            scalar(connection, "SHOW statement_timeout"));
      }
    }

    @ReadReplica
    @Transactional(readOnly = true)
    public String readCancellation() throws SQLException {
      try (Connection connection = dataSource.getConnection()) {
        assertTrue(
            connection
                .getMetaData()
                .getURL()
                .contains(AbstractIntegrationTest.READ_POOL_APPLICATION_NAME));
        assertEquals("1s", scalar(connection, "SHOW statement_timeout"));
        assertEquals("contract-init", scalar(connection, "SHOW application_name"));
        assertEquals("42", scalar(connection, "SELECT 42"));
        SQLException failure =
            assertThrows(SQLException.class, () -> scalar(connection, "SELECT pg_sleep(2)"));
        assertEquals("42", scalar(connection, "SELECT 42"));
        return failure.getSQLState();
      }
    }
  }

  private static String scalar(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }
}
