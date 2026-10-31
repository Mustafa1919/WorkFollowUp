package com.app.tracker.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class TelemetryConsumerTruncateTest {

  @Test
  void keepsPrefixAndLeavesShortValuesUntouched() {
    assertEquals("a".repeat(200), TelemetryConsumer.truncate("a".repeat(250), 200));
    String ok = "IllegalStateException";
    assertSame(ok, TelemetryConsumer.truncate(ok, 200));
    assertNull(TelemetryConsumer.truncate(null, 255));
  }

  @Test
  void doesNotSplitSurrogatePair() {
    String s = "a".repeat(19) + "😀" + "zzz";
    assertEquals("a".repeat(19), TelemetryConsumer.truncate(s, 20));
    assertEquals("a".repeat(19) + "😀", TelemetryConsumer.truncate(s, 21));
  }
}
