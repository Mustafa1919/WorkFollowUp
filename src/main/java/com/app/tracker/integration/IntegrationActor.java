package com.app.tracker.integration;

import java.util.UUID;

/**
 * Webhook kaynakli degisikliklerin {@code task_events.actor_id} degeri. V13 migration'i bu
 * kullaniciyi tohumlar (giris yapilamaz, hicbir workspace'e uye degil).
 */
public final class IntegrationActor {

  private IntegrationActor() {}

  public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
}
