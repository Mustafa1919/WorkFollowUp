package com.app.tracker.core.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.app.tracker.core.AbstractIntegrationTest;
import com.app.tracker.core.exception.BusinessRuleException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 3 — {@code KafkaConsumerConfig}'teki {@code DefaultErrorHandler}
 * bean'inin gercekten TUM {@code @KafkaListener}'lara (sadece kod ornegindeki degil, Boot'un
 * otomatik kesfettigi HERHANGI birine) uygulandigini kanitlar: kalici bir hata
 * (BusinessRuleException, not-retryable listesinde) fırlatan bir dinleyici, tekrar denemeden
 * dogrudan {@code <topic>-dlt}'ye dusmelidir.
 *
 * <p><b>Doc duzeltmesi:</b> PHASE_2_DETAILED_DESIGN.md Bolum 3.2 "DLT Adlandirma Standardi: {@code
 * <topic>.DLT}" diyor — bu YANLIS. Gercek Spring Kafka 4.1.1 varsayilani ({@code
 * DeadLetterPublishingRecoverer.DEFAULT_DESTINATION_RESOLVER}) {@code <topic>-dlt} (kucuk harf,
 * tire) uretir; bu test ilk calistiginda gercek broker'a karsi tam olarak bunu dogruladi.
 */
@SpringBootTest
class KafkaConsumerConfigIntegrationTest extends AbstractIntegrationTest {

  private static final String SOURCE_TOPIC = "test.poison-pill.events";

  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private PoisonPillListener poisonPillListener;

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Test
  void nonRetryableExceptionGoesStraightToDlt() {
    poisonPillListener.attemptCount.set(0);
    kafkaTemplate.send(SOURCE_TOPIC, "poison-key", "poison-value");

    ConsumerRecord<String, String> dltRecord = pollForRecord(SOURCE_TOPIC + "-dlt", "poison-key");

    assertEquals("poison-value", dltRecord.value());
    // BusinessRuleException not-retryable listesinde: 1 deneme, retry YOK.
    assertEquals(1, poisonPillListener.attemptCount.get());
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
    return fail("15 saniye icinde DLT'de beklenen mesaj gelmedi: key=" + expectedKey);
  }

  @TestConfiguration
  static class PoisonPillTestConfig {

    @Bean
    PoisonPillListener poisonPillListener() {
      return new PoisonPillListener();
    }
  }

  static class PoisonPillListener {

    final AtomicInteger attemptCount = new AtomicInteger();

    @KafkaListener(
        topics = SOURCE_TOPIC,
        groupId = "poison-pill-test",
        properties = "auto.offset.reset=earliest")
    void onMessage(String value) {
      attemptCount.incrementAndGet();
      throw new BusinessRuleException("Kasitli test hatasi: " + value);
    }
  }
}
