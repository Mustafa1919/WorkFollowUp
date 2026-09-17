package com.app.tracker.core;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Faz1+'daki tum entegrasyon testleri bu sinifi extend eder. Postgres ve Kafka container'lari test
 * JVM'i boyunca tek sefer ayaga kalkar ve paylasilir (bkz. PHASE_0, Bolum 3, madde 3 — CI'da
 * Testcontainers ile gercek altyapi).
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

  static {
    POSTGRES.start();
    KAFKA.start();
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }
}
