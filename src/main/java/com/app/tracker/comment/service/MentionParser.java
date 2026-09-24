package com.app.tracker.comment.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code @[<userId>]} sozdizimini saf (I/O'suz) ayristirir — serbest isim eslestirme BILEREK YOK
 * (plan dokumani: isim degisince eslestirme bozulur). Frontend autocomplete ile token'i ekler,
 * sunucu burada cikarip workspace uye listesine karsi dogrular (CommentService#resolveMentions).
 */
public final class MentionParser {

  /** Tek bir yorumda en fazla bu kadar mention islenir — gurultuya/kotuye kullanima karsi. */
  static final int MAX_MENTIONS = 20;

  private static final Pattern TOKEN =
      Pattern.compile(
          "@\\[([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\]");

  private MentionParser() {}

  /** Sirali, tekillestirilmis, en fazla {@value #MAX_MENTIONS} adet gecerli UUID doner. */
  public static List<UUID> extract(String body) {
    if (body == null || body.isBlank()) {
      return List.of();
    }
    LinkedHashSet<UUID> found = new LinkedHashSet<>();
    Matcher matcher = TOKEN.matcher(body);
    while (matcher.find() && found.size() < MAX_MENTIONS) {
      try {
        found.add(UUID.fromString(matcher.group(1)));
      } catch (IllegalArgumentException ignored) {
        // Regex zaten UUID karakter kumesini zorluyor; pratikte hic tetiklenmez, savunma amacli.
      }
    }
    return List.copyOf(found);
  }
}
