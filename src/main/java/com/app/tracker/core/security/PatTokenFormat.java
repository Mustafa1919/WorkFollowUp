package com.app.tracker.core.security;

/**
 * ADR-0018 -- kisisel erisim token'lari (PAT) icin sabit onek. JWT (uc nokta-ayrik base64url
 * segment) ile PAT'i (tek opak dizge) {@code Authorization: Bearer} icinde ayirt etmenin tek yolu
 * bu onek -- JwtAuthenticationFilter ve PatAuthenticationFilter ayni header'i okur, hangisi islerse
 * digeri es gecer (cift islenme veya cift DB sorgusu olmaz).
 */
public final class PatTokenFormat {

  public static final String PREFIX = "wf_pat_";

  private PatTokenFormat() {}

  public static boolean matches(String bearerToken) {
    return bearerToken != null && bearerToken.startsWith(PREFIX);
  }

  public static String generateRawToken() {
    return PREFIX + TokenHasher.generateRawToken();
  }

  /** Listede gosterilecek maskeli onizleme, orn. {@code wf_pat_AbC123...}. */
  public static String previewOf(String rawToken) {
    int visibleLength = Math.min(rawToken.length(), PREFIX.length() + 6);
    return rawToken.substring(0, visibleLength) + "...";
  }
}
