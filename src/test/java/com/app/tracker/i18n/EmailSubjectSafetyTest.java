package com.app.tracker.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.tracker.notification.email.EmailContent;
import com.app.tracker.notification.email.EmailTemplates;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailSubjectSafetyTest {
  private static final String BASE = "https://tracker.example";
  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final String LINK = BASE + "/projects/" + PROJECT + "?task=" + TASK;

  static Stream<Arguments> separators() {
    return Stream.of(
        Arguments.of("CR", "\r"),
        Arguments.of("LF", "\n"),
        Arguments.of("CRLF", "\r\n"),
        Arguments.of("TAB", "\t"),
        Arguments.of("U+2028", "\u2028"),
        Arguments.of("U+2029", "\u2029"),
        Arguments.of("U+0085", "\u0085"),
        Arguments.of("ardışık boşluk", "    "),
        Arguments.of("karışık", " \r\n\t\u2028\u2029\u0085  "));
  }

  private EmailContent taskMail(String kind, String key, String title) {
    return kind.equals("assignment")
        ? EmailTemplates.taskAssignedEmail(BASE, PROJECT, TASK, key, 7, title)
        : EmailTemplates.mentionEmail(BASE, PROJECT, TASK, key, 7, title);
  }

  @ParameterizedTest(name = "K9 çalışma alanı {0}")
  @MethodSource("separators")
  void K9_invitationSubjectIsSingleLineAndBodyIsPreserved(String name, String separator) {
    String workspace = "  Çağrı" + separator + "Ekibi  ";
    var content = EmailTemplates.workspaceInviteEmail(BASE, workspace, "VIEWER", "abc");
    assertThat(content.subject()).isEqualTo("Çağrı Ekibi çalışma alanına davet edildin");
    assertThat(content.text())
        .isEqualTo(
            workspace
                + " çalışma alanına İzleyici rolüyle davet edildin (7 gün geçerli): "
                + BASE
                + "/invitations/abc");
    assertThat(content.html()).contains("<strong>" + workspace + "</strong>");
  }

  @ParameterizedTest(name = "K9 görev anahtarı {0}")
  @MethodSource("separators")
  void K9_taskKeySeparatorsAreNormalizedOnlyInSubject(String name, String separator) {
    String key = "ÇĞ" + separator + "EKİP";
    String title = "Başlık\r\nİkinci\u2028satır";
    var assigned = taskMail("assignment", key, title);
    var mention = taskMail("mention", key, title);
    assertThat(assigned.subject()).isEqualTo("Sana bir görev atandı: ÇĞ EKİP-7");
    assertThat(mention.subject()).isEqualTo("Bir yorumda etiketlendin: ÇĞ EKİP-7");
    assertThat(assigned.text()).isEqualTo(key + "-7 \"" + title + "\" sana atandı: " + LINK);
    assertThat(mention.text())
        .isEqualTo(key + "-7 \"" + title + "\" görevindeki bir yorumda etiketlendin: " + LINK);
    assertThat(assigned.html()).contains("<strong>" + key + "-7</strong> — " + title);
    assertThat(mention.html()).contains("<strong>" + key + "-7</strong> — " + title);
  }

  static IntStream controls() {
    return IntStream.concat(IntStream.rangeClosed(0, 31), IntStream.of(127));
  }

  @ParameterizedTest(name = "K9 kontrol karakteri {0}")
  @MethodSource("controls")
  void K9_allControlCharactersBecomeSingleSpaces(int codePoint) {
    String value = "Çığ" + (char) codePoint + "Ekip";
    assertThat(EmailTemplates.workspaceInviteEmail(BASE, value, "VIEWER", "abc").subject())
        .isEqualTo("Çığ Ekip çalışma alanına davet edildin");
    assertThat(taskMail("assignment", value, "Başlık").subject())
        .isEqualTo("Sana bir görev atandı: Çığ Ekip-7");
    assertThat(taskMail("mention", value, "Başlık").subject())
        .isEqualTo("Bir yorumda etiketlendin: Çığ Ekip-7");
  }

  @ParameterizedTest
  @ValueSource(strings = {"assignment", "mention"})
  void K9_taskKeyIsTrimmedBeforeAppendingToSubject(String kind) {
    var content = taskMail(kind, " \tÇĞ\r\n  ", "Başlık");
    assertThat(content.subject())
        .isEqualTo(
            kind.equals("assignment")
                ? "Sana bir görev atandı: ÇĞ-7"
                : "Bir yorumda etiketlendin: ÇĞ-7");
  }

  @Test
  void K9_normalNameAndKeyArePreserved() {
    assertThat(EmailTemplates.workspaceInviteEmail(BASE, "Çağrı Ekibi", "VIEWER", "abc").subject())
        .isEqualTo("Çağrı Ekibi çalışma alanına davet edildin");
    assertThat(taskMail("assignment", "ÇĞ-EKİP", "Görev başlığı").subject())
        .isEqualTo("Sana bir görev atandı: ÇĞ-EKİP-7");
    assertThat(taskMail("mention", "ÇĞ-EKİP", "Görev başlığı").subject())
        .isEqualTo("Bir yorumda etiketlendin: ÇĞ-EKİP-7");
  }
}
