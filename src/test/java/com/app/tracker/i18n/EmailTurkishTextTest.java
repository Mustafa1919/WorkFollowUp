package com.app.tracker.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.tracker.notification.email.EmailContent;
import com.app.tracker.notification.email.EmailProperties;
import com.app.tracker.notification.email.ResilientEmailSender;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

class EmailTurkishTextTest {
  private void textParts(Part part, List<Part> found) throws Exception {
    if (part.isMimeType("multipart/*")) {
      var parts = (Multipart) part.getContent();
      for (int i = 0; i < parts.getCount(); i++) {
        textParts(parts.getBodyPart(i), found);
      }
    } else if (part.isMimeType("text/*")) {
      found.add(part);
    }
  }

  @Test
  void K5_subjectAndBothBodiesPreserveUtf8OnWire() throws Exception {
    Session session = Session.getInstance(new Properties());
    var mime = new MimeMessage(session);
    var mail = mock(JavaMailSender.class);
    when(mail.createMimeMessage()).thenReturn(mime);
    String subject = "Çalışma alanı doğrulaması: ığüşöçİ";
    String text = "Göreviniz İncelemede. Şifre sıfırlama bağlantısı.";
    String html = "<p>Göreviniz İncelemede. Şifre sıfırlama bağlantısı.</p>";
    new ResilientEmailSender(mail, new EmailProperties())
        .send("ayse@example.com", new EmailContent(subject, text, html));
    verify(mail).send(mime);
    mime.saveChanges();
    var bytes = new ByteArrayOutputStream();
    mime.writeTo(bytes);
    assertThat(bytes.toString(StandardCharsets.US_ASCII)).containsIgnoringCase("=?UTF-8?");
    var decoded = new MimeMessage(session, new ByteArrayInputStream(bytes.toByteArray()));
    assertThat(decoded.getSubject()).isEqualTo(subject);
    List<Part> parts = new ArrayList<>();
    textParts(decoded, parts);
    assertThat(parts).hasSize(2);
    for (Part part : parts) {
      assertThat(part.getContentType()).containsIgnoringCase("charset=UTF-8");
      assertThat(part.getContent()).isEqualTo(part.isMimeType("text/html") ? html : text);
    }
  }
}
