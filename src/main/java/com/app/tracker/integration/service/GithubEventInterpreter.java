package com.app.tracker.integration.service;

import com.app.tracker.task.model.TaskStatus;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Bir GitHub olayini "hangi gorev(ler) hangi duruma ILERLETILMEK isteniyor" niyetine cevirir. SAF
 * fonksiyondur (DB/Kafka yok), bu yuzden tum eslesme kurallari birim testle sinanir.
 *
 * <p>Kurallar (PHASE_3'un "commit mesajindaki ENG-101'i bulup durumu gunceller" ornegi
 * somutlastirildi):
 *
 * <ul>
 *   <li>{@code push} (silme degil) -> {@code In Progress}; referans: dal adi + commit mesajlari
 *   <li>{@code pull_request opened/reopened} -> {@code Review} (taslak PR ise {@code In Progress});
 *       {@code ready_for_review} -> {@code Review}; {@code closed} + merged -> {@code Done} (merge
 *       edilmeden kapanan PR durumu DEGISTIRMEZ); referans: baslik + govde + dal adi
 *   <li>diger her sey (synchronize, edited, labeled, ...) yok sayilir
 * </ul>
 *
 * Gorev anahtari bicimi proje anahtarina uyar: {@code ENG-101} (buyuk harf, 2-10 karakter). Dal
 * adlarinda kucuk harf yaygin oldugu icin ({@code eng-101-fix}) YALNIZ dal adi buyuk/kucuk harfe
 * duyarsiz eslenir; serbest metinde ({@code sha-256}, {@code utf-8} gibi) yanlis pozitifi azaltmak
 * icin buyuk harf sarttir.
 */
public final class GithubEventInterpreter {

  /** Tek olay basina islenecek en fazla gorev referansi (DB islerini sinirlar). */
  static final int MAX_REFERENCES = 50;

  private static final String KEY = "([A-Z][A-Z0-9]{1,9})-(\\d{1,9})";
  private static final Pattern TEXT_PATTERN = Pattern.compile("\\b" + KEY + "\\b");
  private static final Pattern BRANCH_PATTERN =
      Pattern.compile("\\b" + KEY + "\\b", Pattern.CASE_INSENSITIVE);

  /** Ornek: {@code ENG-101}. */
  public record TaskReference(String projectKey, int taskNumber) {}

  /** {@code references} bos degilse, hepsi {@code targetStatus}'e ILERLETILMEK istenir. */
  public record StatusIntent(String targetStatus, Set<TaskReference> references) {

    public StatusIntent {
      references = Set.copyOf(references);
    }
  }

  private GithubEventInterpreter() {}

  public static Optional<StatusIntent> interpret(String githubEvent, JsonNode payload) {
    return switch (githubEvent) {
      case "push" -> interpretPush(payload);
      case "pull_request" -> interpretPullRequest(payload);
      default -> Optional.empty();
    };
  }

  private static Optional<StatusIntent> interpretPush(JsonNode payload) {
    if (payload.path("deleted").asBoolean(false)) {
      return Optional.empty();
    }
    Set<TaskReference> references = new LinkedHashSet<>();
    collect(BRANCH_PATTERN, payload.path("ref").asString(""), references);
    for (JsonNode commit : payload.path("commits")) {
      collect(TEXT_PATTERN, commit.path("message").asString(""), references);
    }
    return intent(TaskStatus.IN_PROGRESS, references);
  }

  private static Optional<StatusIntent> interpretPullRequest(JsonNode payload) {
    JsonNode pr = payload.path("pull_request");
    String target =
        switch (payload.path("action").asString("")) {
          case "opened", "reopened" ->
              pr.path("draft").asBoolean(false) ? TaskStatus.IN_PROGRESS : TaskStatus.REVIEW;
          case "ready_for_review" -> TaskStatus.REVIEW;
          case "closed" -> pr.path("merged").asBoolean(false) ? TaskStatus.DONE : null;
          default -> null;
        };
    if (target == null) {
      return Optional.empty();
    }
    Set<TaskReference> references = new LinkedHashSet<>();
    collect(TEXT_PATTERN, pr.path("title").asString(""), references);
    collect(TEXT_PATTERN, pr.path("body").asString(""), references);
    collect(BRANCH_PATTERN, pr.path("head").path("ref").asString(""), references);
    return intent(target, references);
  }

  private static Optional<StatusIntent> intent(String target, Set<TaskReference> references) {
    return references.isEmpty()
        ? Optional.empty()
        : Optional.of(new StatusIntent(target, references));
  }

  private static void collect(Pattern pattern, String text, Set<TaskReference> into) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find() && into.size() < MAX_REFERENCES) {
      // Locale.ROOT: Turkce locale'de "eng".toUpperCase() "ENG" degil "ENG"in noktali-I varyantina
      // ("i" -> "İ") kayabilir; proje anahtari eslesmesi locale'den bagimsiz olmali.
      into.add(
          new TaskReference(
              matcher.group(1).toUpperCase(Locale.ROOT), Integer.parseInt(matcher.group(2))));
    }
  }
}
