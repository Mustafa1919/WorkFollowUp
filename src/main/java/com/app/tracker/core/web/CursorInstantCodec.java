package com.app.tracker.core.web;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.regex.Pattern;

/** Keyset cursor zamanini nanosaniye hassasiyetinde tasir; eski milisaniye bicimini de okur. */
public final class CursorInstantCodec {

  private static final Pattern TIMESTAMP = Pattern.compile("[0-9]+(?:\\.[0-9]{1,9})?");

  private CursorInstantCodec() {}

  public static String encode(Instant instant) {
    if (instant.getEpochSecond() < 0) {
      throw new IllegalArgumentException("Gecersiz cursor zamani.");
    }
    String nanos = Integer.toString(instant.getNano());
    return instant.getEpochSecond() + "." + "0".repeat(9 - nanos.length()) + nanos;
  }

  public static Instant decode(String value) {
    if (value == null || !TIMESTAMP.matcher(value).matches()) {
      throw new IllegalArgumentException("Gecersiz cursor zamani.");
    }
    int dot = value.indexOf('.');
    try {
      if (dot < 0) {
        return Instant.ofEpochMilli(Long.parseLong(value));
      }
      long seconds = Long.parseLong(value.substring(0, dot));
      String fraction = value.substring(dot + 1);
      int nanos = Integer.parseInt(fraction + "0".repeat(9 - fraction.length()));
      return Instant.ofEpochSecond(seconds, nanos);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("Gecersiz cursor zamani.", e);
    }
  }
}
