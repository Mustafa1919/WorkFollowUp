package com.app.tracker.user.model;

import java.util.Objects;

/**
 * SEC-14 — e-posta adresinin sistemdeki TEK kanonik bicimi: bas/son (Unicode) bosluk kirpilir,
 * yalniz ASCII {@code A..Z} kucultulur; diger butun karakterler (ASCII disi harfler dahil) aynen
 * kalir. {@code toLowerCase} bilerek kullanilmaz: Turkce {@code İ} gibi harfleri iki kod noktasina
 * acabilir ve V38'deki {@code translate(...)} CHECK kisitiyla uyusmazdi.
 */
public final class EmailAddresses {

  private EmailAddresses() {}

  /** null → NullPointerException; aksi halde strip() + yalniz 'A'..'Z' → 'a'..'z'. */
  public static String canonical(String email) {
    String stripped = Objects.requireNonNull(email, "email").strip();
    char[] chars = stripped.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
  }
}
