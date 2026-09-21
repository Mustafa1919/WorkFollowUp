package com.app.tracker.notification;

import com.app.tracker.notification.service.SlackSender;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Entegrasyon testlerinde GERCEK Slack'e cikilmasin diye {@code @Primary}: test agacindaki duz
 * {@code @Component} olarak TUM {@code @SpringBootTest} context'lerinde gercek gondericinin yerine
 * gecer (bkz. {@code ResilientSlackSender}; onun HTTP/devre kesici davranisi ayri birim testte,
 * yerel sunucuya karsi dogrulanir).
 *
 * <p>Durum bilerek STATIK: Spring test context'leri JVM'de onbelleklenir ve HEPSININ Kafka
 * listener'lari ayni consumer group'ta calisir; bir olayi hangi context'in tuketecegi belirsizdir.
 * Statik kayit, hangi context tuketirse tuketsin testin gorebilmesini saglar. Testler kendi
 * tenant'ina ozgu (benzersiz) adresle suzer ve durumu birbirine sizdirmaz.
 */
@Component
@Primary
@Profile("!migrate")
public class RecordingSlackSender implements SlackSender {

  public record Sent(URI url, String text) {}

  private static final List<Sent> SENT = new CopyOnWriteArrayList<>();
  private static final Map<URI, RuntimeException> FAILURES = new ConcurrentHashMap<>();

  public static List<String> textsSentTo(URI url) {
    return SENT.stream().filter(sent -> sent.url().equals(url)).map(Sent::text).toList();
  }

  /** {@code url}'e yapilacak sonraki TUM gonderimler bu hatayla basarisiz olur. */
  public static void failWith(URI url, RuntimeException failure) {
    FAILURES.put(url, failure);
  }

  public static void stopFailing(URI url) {
    FAILURES.remove(url);
  }

  @Override
  public void send(URI webhookUrl, String text) {
    RuntimeException failure = FAILURES.get(webhookUrl);
    if (failure != null) {
      throw failure;
    }
    SENT.add(new Sent(webhookUrl, text));
  }
}
