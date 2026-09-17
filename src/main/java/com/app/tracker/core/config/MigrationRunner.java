package com.app.tracker.core.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * "migrate" profili altında koşan Kubernetes Job'ın giriş noktası. Flyway, context başlatılırken
 * migration'ları uygular; bu runner yalnızca işi bittikten sonra JVM'i temiz bir exit code ile
 * kapatır (bkz. PHASE_0, Bölüm 4.2 — migration ayrı bir Job, uygulama startup'ı değil).
 */
@Component
@Profile("migrate")
public class MigrationRunner implements ApplicationRunner {

  private final ApplicationContext context;

  public MigrationRunner(ApplicationContext context) {
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    System.exit(SpringApplication.exit(context, () -> 0));
  }
}
