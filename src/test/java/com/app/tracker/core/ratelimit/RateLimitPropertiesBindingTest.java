package com.app.tracker.core.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.app.tracker.core.web.ClientIpProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Ayar adlarinin ve alan bazli ezmenin gercek Spring Boot baglamasiyla calistigini dogrular. */
class RateLimitPropertiesBindingTest {

  @Test
  void bindsEnabledAndPartialRuleOverrides() {
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of(
                    "app.rate-limit.enabled", "false",
                    "app.rate-limit.rules.login.limit", "7",
                    "app.rate-limit.rules.invitation.window", "PT2H")));
    RateLimitProperties props = binder.bindOrCreate("app.rate-limit", RateLimitProperties.class);
    assertFalse(props.isEnabled());
    assertEquals(7L, props.getRules().get("login").getLimit());
    assertNull(props.getRules().get("login").getWindow());
    assertEquals(Duration.ofHours(2), props.getRules().get("invitation").getWindow());
  }

  @Test
  void bindsCommaSeparatedTrustedProxies() {
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of("app.http.trusted-proxies", "127.0.0.0/8, ::1/128, 10.0.0.0/8")));
    ClientIpProperties props = binder.bindOrCreate("app.http", ClientIpProperties.class);
    assertEquals(List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8"), props.getTrustedProxies());
  }
}
