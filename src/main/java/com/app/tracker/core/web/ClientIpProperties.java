package com.app.tracker.core.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Istemci IP cozumunde guvenilen proxy aglari (CIDR); bkz. {@link ClientIpResolver}. Varsayilan:
 * loopback + ozel aglar (Docker agi; uretimde Caddy -> nginx -> backend zinciri).
 */
@ConfigurationProperties(prefix = "app.http")
public class ClientIpProperties {

  private List<String> trustedProxies =
      new ArrayList<>(
          List.of(
              "127.0.0.0/8",
              "::1/128",
              "10.0.0.0/8",
              "172.16.0.0/12",
              "192.168.0.0/16",
              "fc00::/7"));

  public List<String> getTrustedProxies() {
    return List.copyOf(trustedProxies);
  }

  public void setTrustedProxies(List<String> trustedProxies) {
    this.trustedProxies = new ArrayList<>(trustedProxies);
  }
}
