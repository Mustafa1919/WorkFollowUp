package com.app.tracker.core.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Is kurallarindaki "bugun" kavrami (orn. gecmis tarihli gorev yasagi) pod'un JVM saat dilimine
 * degil, urunun is saat dilimine baglidir: pod'lar genelde UTC'de kosar, Istanbul'da gece
 * 00:00-03:00 arasi UTC'ye gore hala "dun"dur. Tenant bazli saat dilimi yok (bilinen sinir).
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock businessClock(@Value("${app.business-time-zone:Europe/Istanbul}") String zone) {
    return Clock.system(ZoneId.of(zone));
  }
}
