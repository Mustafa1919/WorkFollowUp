package com.app.tracker.notification.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailSubjectSingleLineTest {

  @Test
  void normalizesInvitationSubjectToSingleLineAndPreservesBodies() {
    String workspaceName = "  Çığ\r\n\t  & <ekip>\u2028Özel\u2029Alan\u0085Son  ";
    EmailContent content =
        EmailTemplates.workspaceInviteEmail(
            "https://tracker.example", workspaceName, "VIEWER", "abc");

    assertEquals(
        "Çığ &amp; &lt;ekip&gt; Özel Alan Son çalışma alanına davet edildin", content.subject());
    String escapedName = "  Çığ\r\n\t  &amp; &lt;ekip&gt;\u2028Özel\u2029Alan\u0085Son  ";
    assertEquals(
        escapedName
            + " çalışma alanına İzleyici rolüyle davet edildin (7 gün geçerli): "
            + "https://tracker.example/invitations/abc",
        content.text());
    assertTrue(content.html().contains("<strong>" + escapedName + "</strong>"));
  }

  @Test
  void replacesAllControlCharactersWithSpacesInSubject() {
    for (int codePoint = 0; codePoint <= 0x9f; codePoint++) {
      if (!Character.isISOControl(codePoint)) {
        continue;
      }
      String workspaceName = "Çığ" + (char) codePoint + "Ekip";
      EmailContent content =
          EmailTemplates.workspaceInviteEmail(
              "https://tracker.example", workspaceName, "VIEWER", "abc");
      assertEquals(
          "Çığ Ekip çalışma alanına davet edildin", content.subject(), "Kod: " + codePoint);
      assertTrue(content.text().startsWith(workspaceName));
      assertTrue(content.html().contains("<strong>" + workspaceName + "</strong>"));
    }
  }

  @Test
  void normalizesTaskKeyToSingleLineOnlyInSubjects() {
    UUID projectId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    String projectKey = " ÇĞ\r\n\u2028  EKİP";
    String title = "Başlık\r\nİkinci satır";
    String taskRef = projectKey + "-7";
    String link = "https://tracker.example/projects/" + projectId + "?task=" + taskId;
    EmailContent assigned =
        EmailTemplates.taskAssignedEmail(
            "https://tracker.example", projectId, taskId, projectKey, 7, title);
    EmailContent mention =
        EmailTemplates.mentionEmail(
            "https://tracker.example", projectId, taskId, projectKey, 7, title);

    assertEquals("Sana bir görev atandı: ÇĞ EKİP-7", assigned.subject());
    assertEquals("Bir yorumda etiketlendin: ÇĞ EKİP-7", mention.subject());
    assertEquals(taskRef + " \"" + title + "\" sana atandı: " + link, assigned.text());
    assertEquals(
        taskRef + " \"" + title + "\" görevindeki bir yorumda etiketlendin: " + link,
        mention.text());
    assertTrue(assigned.html().contains("<strong>" + taskRef + "</strong> — " + title));
    assertTrue(mention.html().contains("<strong>" + taskRef + "</strong> — " + title));
  }
}
