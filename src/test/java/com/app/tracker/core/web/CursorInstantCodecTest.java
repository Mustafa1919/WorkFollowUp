package com.app.tracker.core.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CursorInstantCodecTest {

  @Test
  void encode_preservesNanosecondsWithNineDigits() {
    Instant instant = Instant.parse("2026-10-31T10:00:00.123456Z");
    assertEquals("1793440800.123456000", CursorInstantCodec.encode(instant));
    for (int nanos : new int[] {0, 1, 123456000, 123456789, 999999999}) {
      Instant value = Instant.ofEpochSecond(1793440800L, nanos);
      String encoded = CursorInstantCodec.encode(value);
      assertEquals(9, encoded.substring(encoded.indexOf('.') + 1).length());
      assertEquals(value, CursorInstantCodec.decode(encoded));
    }
    assertEquals("0.000000000", CursorInstantCodec.encode(Instant.EPOCH));
    assertEquals(Instant.MAX, CursorInstantCodec.decode(CursorInstantCodec.encode(Instant.MAX)));
  }

  @Test
  void decode_padsFractionOnTheRight() {
    String digits = "123456789";
    for (int length = 1; length <= 9; length++) {
      String fraction = digits.substring(0, length);
      assertEquals(
          Instant.parse("2026-10-31T10:00:00." + fraction + "Z"),
          CursorInstantCodec.decode("1793440800." + fraction));
    }
    assertEquals(
        Instant.parse("2026-10-31T10:00:00.5Z"), CursorInstantCodec.decode("1793440800.5"));
  }

  @Test
  void decode_acceptsLegacyMilliseconds() {
    assertEquals(
        Instant.parse("2026-10-31T10:00:00.123Z"), CursorInstantCodec.decode("1793440800123"));
    assertEquals(Instant.EPOCH, CursorInstantCodec.decode("0"));
    assertEquals(
        Instant.ofEpochMilli(Long.MAX_VALUE), CursorInstantCodec.decode("9223372036854775807"));
  }

  @Test
  void decode_rejectsMalformedOrOutOfRangeValues() {
    for (String value :
        new String[] {
          "",
          "abc",
          "1.2.3",
          "-5",
          "-1.5",
          "1.",
          ".5",
          "1.1234567890",
          "1 .5",
          "+1",
          " 1",
          "1\n",
          "9223372036854775808",
          "9223372036854775808.1",
          "9223372036854775807.1"
        }) {
      assertThrows(IllegalArgumentException.class, () -> CursorInstantCodec.decode(value), value);
    }
    assertThrows(IllegalArgumentException.class, () -> CursorInstantCodec.decode(null));
  }

  @Test
  void encode_rejectsInstantsBeforeEpoch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CursorInstantCodec.encode(Instant.ofEpochSecond(-1, 999999999)));
  }
}
