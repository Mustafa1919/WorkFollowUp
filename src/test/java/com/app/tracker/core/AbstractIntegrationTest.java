package com.app.tracker.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Faz1+'daki tum entegrasyon testleri bu sinifi extend eder. Postgres, Kafka ve Redis
 * container'lari test JVM'i boyunca tek sefer ayaga kalkar ve paylasilir (bkz. PHASE_0, Bolum 3,
 * madde 3 — CI'da Testcontainers ile gercek altyapi). Redis, Faz2'ye kadar hicbir testte
 * gerekmemisti (BruteForceGuard/IdempotencyFilter'i gercekten calistiran ilk test SystemAdmin*Test
 * oldu) — bu yuzden GenericContainer olarak burada eklendi.
 *
 * <p>Container'in POSTGRES_USER'i ({@code app_migrator}) tablo sahibidir ve RLS'ten muaftir
 * (PHASE_1_DETAILED_DESIGN Bolum 3.1, Kural 2) — bu yuzden Spring'in gercek DataSource'u {@code
 * app_runtime} rolune baglanmalidir, aksi halde RLS testleri her zaman "gecer" gorunur. Bu sinif
 * container ayaga kalktiginda: (1) prod'daki docker-init script'ini birebir tekrarlayarak {@code
 * app_runtime} rolunu ve yetkilerini kurar, (2) Flyway migration'lari {@code app_migrator} ile
 * calistirir — TUMU Spring context olusmadan ONCE, boylece Spring'in DataSource'u dogrudan {@code
 * app_runtime} olarak acilabilir.
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("tracker_test")
          .withUsername("app_migrator")
          .withPassword("app_migrator");

  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  static {
    POSTGRES.start();
    KAFKA.start();
    REDIS.start();
    provisionAppRuntimeRole();
    runMigrations();
  }

  private static void provisionAppRuntimeRole() {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE ROLE app_runtime WITH LOGIN PASSWORD 'app_runtime'");
      statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO app_runtime");
      statement.execute(
          "ALTER DEFAULT PRIVILEGES FOR ROLE app_migrator IN SCHEMA public "
              + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime");
    } catch (Exception e) {
      throw new IllegalStateException("app_runtime rolu kurulamadi", e);
    }
  }

  private static void runMigrations() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "app_runtime");
    registry.add("spring.datasource.password", () -> "app_runtime");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }
}
