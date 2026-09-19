package com.app.tracker.core.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 3.3, madde 3 — replay aracinin gercek Kafka'ya karsi uctan uca
 * kaniti: bir DLT topic'ine dogrudan yazilan (poison-pill senaryosunu taklit eden) kayitlar, {@code
 * KafkaReplayService.replay} ile orijinal topic'e dogru key/value ile geri gonderiliyor mu.
 */
@SpringBootTest
class KafkaReplayServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private KafkaReplayService replayService;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Test
  void replaysDltRecordsBackToOriginalTopic() {
    String dltTopic = "replay-test.events-dlt";
    String originalTopic = "replay-test.events";
    String uniqueKey = "replay-key-" + UUID.randomUUID();

    long offset = produceAndGetOffset(dltTopic, uniqueKey, "replayed-value");

    KafkaReplayService.ReplayResult result = replayService.replay(dltTopic, 0, offset, offset);

    assertEquals(originalTopic, result.originalTopic());
    assertEquals(1, result.replayedCount());

    ConsumerRecord<String, String> record = pollForRecord(originalTopic, uniqueKey);
    assertEquals("replayed-value", record.value());
  }

  @Test
  void rejectsTopicsWithoutDltSuffix() {
    assertThrows(
        BusinessRuleException.class, () -> replayService.replay("not-a-dlt-topic", 0, 0, 0));
  }

  @Test
  void rejectsInvertedOffsetRange() {
    assertThrows(
        BusinessRuleException.class, () -> replayService.replay("some.topic-dlt", 0, 10, 5));
  }

  private long produceAndGetOffset(String topic, String key, String value) {
    try {
      RecordMetadata metadata =
          kafkaTemplate.send(new ProducerRecord<>(topic, key, value)).get().getRecordMetadata();
      return metadata.offset();
    } catch (Exception e) {
      throw new IllegalStateException("Test verisi Kafka'ya yazilamadi", e);
    }
  }

  private ConsumerRecord<String, String> pollForRecord(String topic, String expectedKey) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
      while (System.currentTimeMillis() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> record : records) {
          if (expectedKey.equals(record.key())) {
            return record;
          }
        }
      }
    }
    return fail("15 saniye icinde replay edilen mesaj gelmedi: key=" + expectedKey);
  }
}
