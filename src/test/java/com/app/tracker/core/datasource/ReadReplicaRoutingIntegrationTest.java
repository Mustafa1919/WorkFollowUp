package com.app.tracker.core.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.app.tracker.analytics.service.AnalyticsQueryService;
import com.app.tracker.core.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Faz 3 / Dilim 3.3 — read DataSource yonlendirmesi. Test ortaminda read havuzu AYNI Postgres'e
 * {@code ApplicationName=tracker-read-pool} ile baglanir (bkz. AbstractIntegrationTest); boylece
 * sorgunun HANGI havuzdan calistigi {@code current_setting('application_name')} ile gozlenir.
 */
@SpringBootTest
@Import(ReadReplicaRoutingIntegrationTest.ProbeConfig.class)
class ReadReplicaRoutingIntegrationTest extends AbstractIntegrationTest {

  @Autowired private Probe probe;
  @Autowired private AnalyticsQueryService analyticsQueryService;

  @Test
  void markedReadOnlyTransactionGoesToReadPool() {
    assertEquals(READ_POOL_APPLICATION_NAME, probe.markedReadOnly());
  }

  @Test
  void readOnlyAloneIsNotEnoughSoCoreReadsKeepReadYourWrites() {
    assertNotEquals(READ_POOL_APPLICATION_NAME, probe.unmarkedReadOnly());
  }

  @Test
  void markedButWritableTransactionStaysOnWritePool() {
    // Isaret + yazma transaction'i: yanlislikla replica'ya yazma denenmemeli.
    assertNotEquals(READ_POOL_APPLICATION_NAME, probe.markedButWritable());
  }

  @Test
  void flagDoesNotLeakToTheNextCallOnTheSameThread() {
    assertEquals(READ_POOL_APPLICATION_NAME, probe.markedReadOnly());
    assertNotEquals(READ_POOL_APPLICATION_NAME, probe.unmarkedReadOnly());
  }

  @Test
  void analyticsQueryServiceIsMarkedAndProxied() {
    // Davranissal kanit Probe ile; burada gercek servisin isaretinin (ve aspect'in uygulandigi
    // proxy'nin) silinmedigi korunur.
    assertNotNull(AnnotationUtils.findAnnotation(AnalyticsQueryService.class, ReadReplica.class));
    assertTrue(AopUtils.isAopProxy(analyticsQueryService));
  }

  @TestConfiguration
  static class ProbeConfig {
    @Bean
    Probe probe(EntityManager entityManager) {
      return new Probe(entityManager);
    }
  }

  /** Transaction sinirlari gercek servislerdeki gibi Spring proxy'sinden gecer. */
  public static class Probe {

    private final EntityManager entityManager;

    Probe(EntityManager entityManager) {
      this.entityManager = entityManager;
    }

    @ReadReplica
    @Transactional(readOnly = true)
    public String markedReadOnly() {
      return applicationName();
    }

    @Transactional(readOnly = true)
    public String unmarkedReadOnly() {
      return applicationName();
    }

    @ReadReplica
    @Transactional
    public String markedButWritable() {
      return applicationName();
    }

    private String applicationName() {
      return (String)
          entityManager
              .createNativeQuery("SELECT current_setting('application_name')")
              .getSingleResult();
    }
  }
}
