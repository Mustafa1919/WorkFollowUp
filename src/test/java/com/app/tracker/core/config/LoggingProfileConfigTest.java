package com.app.tracker.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

/** INJ-07: resolve the real application.yml without initializing JVM-wide logging. */
class LoggingProfileConfigTest {

  private static String consoleFormat(String... profiles) {
    var environment = new StandardEnvironment();
    environment.setActiveProfiles(profiles);
    ConfigDataEnvironmentPostProcessor.applyTo(environment);
    return environment.getProperty("logging.structured.format.console");
  }

  @ParameterizedTest
  @ValueSource(strings = {"prod", "staging"})
  void K1_protectedProfilesSelectEcsConsoleFormat(String profile) {
    assertEquals("ecs", consoleFormat(profile));
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "migrate", "default"})
  void R1_otherProfilesLeaveStructuredConsoleFormatUndefined(String profile) {
    assertNull(consoleFormat("default".equals(profile) ? new String[0] : new String[] {profile}));
  }
}
