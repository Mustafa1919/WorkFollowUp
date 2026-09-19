package com.app.tracker.core.kafka;

import com.app.tracker.core.exception.BusinessRuleException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 3.3, madde 3 — DLT'ye dusen mesajlari, kok neden duzeltildikten
 * sonra orijinal topic'e geri pompalayan "basit versiyon" (belirli bir partition/offset araligini
 * yeniden yayinlayan bir yonetici endpoint'i). {@code KafkaReplayController} bunu {@code
 * SYSTEM_ADMIN}'e acar (bkz. SystemAdminProperties).
 *
 * <p>Idempotency ön sarti (Bolum 3.3, madde 4): bu araç sadece "orijinal topic'e tekrar gönder"
 * yapar; yeniden gönderilen olayin AYNI eventId ile geldigini garanti eder (envelope aynen tasinir,
 * degistirilmez) — consumer'lar idempotent oldugu surece (Faz3 workerlari) tekrar isleme
 * guvenlidir.
 */
@Service
public class KafkaReplayService {

  private static final String DLT_SUFFIX = "-dlt";
  private static final int MAX_BATCH_SIZE = 10_000;
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(3);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final String bootstrapServers;

  public KafkaReplayService(
      KafkaTemplate<String, String> kafkaTemplate,
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    this.kafkaTemplate = kafkaTemplate;
    this.bootstrapServers = bootstrapServers;
  }

  public record ReplayResult(String originalTopic, int replayedCount) {}

  public ReplayResult replay(String dltTopic, int partition, long fromOffset, long toOffset) {
    String originalTopic = originalTopicOf(dltTopic);
    if (fromOffset < 0 || toOffset < fromOffset) {
      throw new BusinessRuleException("Gecersiz offset araligi.");
    }
    long rangeSize = toOffset - fromOffset + 1;
    if (rangeSize > MAX_BATCH_SIZE) {
      throw new BusinessRuleException(
          "Tek seferde en fazla " + MAX_BATCH_SIZE + " mesaj replay edilebilir.");
    }

    int replayedCount = 0;
    TopicPartition topicPartition = new TopicPartition(dltTopic, partition);
    try (KafkaConsumer<String, String> consumer = newConsumer()) {
      consumer.assign(List.of(topicPartition));
      consumer.seek(topicPartition, fromOffset);
      long deadline = System.currentTimeMillis() + IDLE_TIMEOUT.toMillis();
      while (System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
        if (records.isEmpty()) {
          continue;
        }
        deadline = System.currentTimeMillis() + IDLE_TIMEOUT.toMillis();
        boolean reachedToOffset = false;
        for (ConsumerRecord<String, String> record : records) {
          if (record.offset() > toOffset) {
            return new ReplayResult(originalTopic, replayedCount);
          }
          kafkaTemplate.send(originalTopic, record.key(), record.value());
          replayedCount++;
          reachedToOffset = reachedToOffset || record.offset() >= toOffset;
        }
        if (reachedToOffset) {
          break;
        }
      }
    }
    return new ReplayResult(originalTopic, replayedCount);
  }

  private static String originalTopicOf(String dltTopic) {
    if (!dltTopic.endsWith(DLT_SUFFIX)) {
      throw new BusinessRuleException(
          "Bu endpoint sadece '" + DLT_SUFFIX + "' ile biten topic'leri kabul eder.");
    }
    return dltTopic.substring(0, dltTopic.length() - DLT_SUFFIX.length());
  }

  private KafkaConsumer<String, String> newConsumer() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-tool-" + UUID.randomUUID());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new KafkaConsumer<>(props);
  }
}
