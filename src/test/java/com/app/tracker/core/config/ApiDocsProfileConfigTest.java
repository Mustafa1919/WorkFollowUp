package com.app.tracker.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * CFG-03: korumali profillerde springdoc kapali, digerlerinde acik. Bos bir yapilandirma sinifiyla
 * yalniz application.yml'in profil cozumlemesi kosulur — uygulama bean'leri (ve korumali profilin
 * dev anahtar reddi) devreye girmez.
 */
class ApiDocsProfileConfigTest {

  @Configuration(proxyBeanMethods = false)
  static class Empty {}

  private static String property(String key, String... profiles) {
    try (ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(Empty.class)
            .web(WebApplicationType.NONE)
            .profiles(profiles)
            .logStartupInfo(false)
            .run()) {
      Environment env = ctx.getEnvironment();
      return env.getProperty(key);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"prod", "staging"})
  void apiDocsAndSwaggerUiAreDisabledInProtectedProfiles(String profile) {
    assertEquals("false", property("springdoc.api-docs.enabled", profile));
    assertEquals("false", property("springdoc.swagger-ui.enabled", profile));
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "migrate", "default"})
  void apiDocsStayEnabledOutsideProtectedProfiles(String profile) {
    assertNotEquals("false", property("springdoc.api-docs.enabled", profile));
    assertNotEquals("false", property("springdoc.swagger-ui.enabled", profile));
  }
}
