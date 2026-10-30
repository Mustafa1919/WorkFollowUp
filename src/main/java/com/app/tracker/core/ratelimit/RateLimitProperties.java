package com.app.tracker.core.ratelimit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Uc bazli hiz siniri ayarlari (SEC-07). Kural tablosu (yol, yontem, anahtar turu) kodda sabittir
 * ({@link RateLimitRule#DEFAULTS}); burada yalniz kural adina gore sinir/pencere ezilir. Bir alan
 * verilmezse koddaki varsayilan kalir (ornegin yalniz {@code limit} ezilirse pencere degismez).
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

  @Getter @Setter private boolean enabled = true;
  private Map<String, RuleSettings> rules = new LinkedHashMap<>();

  public Map<String, RuleSettings> getRules() {
    return Map.copyOf(rules);
  }

  public void setRules(Map<String, RuleSettings> rules) {
    this.rules = new LinkedHashMap<>(rules);
  }

  /** Tek kuralin ezilebilir alanlari; {@code null} = varsayilan. */
  @Getter
  @Setter
  public static class RuleSettings {
    private Long limit;
    private Duration window;
  }
}
