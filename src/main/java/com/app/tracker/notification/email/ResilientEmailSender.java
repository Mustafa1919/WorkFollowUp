package com.app.tracker.notification.email;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP cagrisi Resilience4j devre kesicisi arkasinda — {@code ResilientSlackSender} ile AYNI desen
 * (bkz. ADR-0006): tek global devre kesici (SMTP tum kullanicilar icin PAYLASILAN tek bagimlilik),
 * yalniz gecici hatalar sayilir, retry burada YOK (Kafka error handler tek retry sorumlusu).
 *
 * <p><b>Bilinen sinir:</b> {@link JavaMailSender}, gercek SMTP durum kodunu (4xx gecici / 5xx
 * kalici) her zaman ayirt edilebilir sekilde iletmez. Basit bir sezgisel kural uygulanir: alici
 * adresi bicim olarak GECERSIZ ise ({@link AddressException}, mesaj kurulurken firlar) kalici
 * sayilir; diger tum SMTP/baglanti hatalari GECICI sayilir (ADR-0010'da kayitli). Bicim kontrolu
 * KATI ayristirmayla yapilir: JavaMail'in varsayilan gevsek ayristiricisi {@code "abc"} gibi alan
 * adsiz degerleri de kabul eder.
 *
 * <p>Zaman asimlari ({@code connectTimeout}/{@code readTimeout}) JavaMail oturumuna burada yazilir;
 * JavaMail'in varsayilani SONSUZ beklemedir — selamlama yollamayan bir SMTP sunucusu tek consumer
 * thread'ini suresiz kilitlerdi.
 */
@Component
@Profile("!migrate")
public class ResilientEmailSender implements EmailSender {

  private final JavaMailSender mailSender;
  private final EmailProperties properties;
  private final CircuitBreaker circuitBreaker;

  public ResilientEmailSender(JavaMailSender mailSender, EmailProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
    applyTimeouts(mailSender, properties);
    this.circuitBreaker =
        CircuitBreaker.of(
            "email",
            CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.getSlidingWindowSize())
                .minimumNumberOfCalls(properties.getMinimumNumberOfCalls())
                .failureRateThreshold(properties.getFailureRateThreshold())
                .waitDurationInOpenState(properties.getOpenStateDuration())
                .permittedNumberOfCallsInHalfOpenState(
                    properties.getPermittedCallsInHalfOpenState())
                .recordExceptions(EmailTransientException.class)
                .ignoreExceptions(EmailPermanentException.class)
                .build());
  }

  @Override
  public void send(String to, EmailContent content) {
    try {
      circuitBreaker.executeRunnable(() -> doSend(to, content));
    } catch (CallNotPermittedException e) {
      throw new EmailTransientException("E-posta devre kesici acik, gonderim yapilmadi", e);
    }
  }

  private static void applyTimeouts(JavaMailSender mailSender, EmailProperties properties) {
    if (mailSender instanceof JavaMailSenderImpl impl) {
      String connect = String.valueOf(properties.getConnectTimeout().toMillis());
      String read = String.valueOf(properties.getReadTimeout().toMillis());
      impl.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", connect);
      impl.getJavaMailProperties().setProperty("mail.smtp.timeout", read);
      impl.getJavaMailProperties().setProperty("mail.smtp.writetimeout", read);
    }
  }

  CircuitBreaker circuitBreaker() {
    return circuitBreaker;
  }

  private void doSend(String to, EmailContent content) {
    MimeMessage message = mailSender.createMimeMessage();
    try {
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(properties.getFrom());
      helper.setTo(new InternetAddress(to, true));
      helper.setSubject(content.subject());
      helper.setText(content.text(), content.html());
    } catch (AddressException e) {
      throw new EmailPermanentException("Gecersiz alici adresi", e);
    } catch (MessagingException e) {
      throw new EmailTransientException("E-posta mesaji kurulamadi", e);
    }
    try {
      mailSender.send(message);
    } catch (MailAuthenticationException | MailSendException e) {
      throw new EmailTransientException("SMTP'ye ulasilamadi: " + e.getClass().getSimpleName(), e);
    } catch (MailException e) {
      throw new EmailTransientException(
          "E-posta gonderilemedi: " + e.getClass().getSimpleName(), e);
    }
  }
}
