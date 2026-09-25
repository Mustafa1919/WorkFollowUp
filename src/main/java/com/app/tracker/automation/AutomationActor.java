package com.app.tracker.automation;

import java.util.UUID;

/**
 * {@code com.app.tracker.integration.IntegrationActor} ile AYNI desen: otomasyon kurallarinin
 * tetikledigi degisiklikler bu sabit sistem kullanicisi adina yazilir (V31, {@code
 * system-automation@tracker.invalid}). Dongu korumasinin temeli budur — bkz. {@code
 * AutomationEventConsumer} javadoc'u.
 */
public final class AutomationActor {

  private AutomationActor() {}

  public static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-00000000a003");
}
