package com.app.tracker.core.kafka;

import com.app.tracker.core.exception.BusinessRuleException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * PHASE_2_DETAILED_DESIGN.md Bolum 3.2 — tek {@code CommonErrorHandler} bean'i, Spring Boot'un
 * otomatik yapilandirdigi {@code ConcurrentKafkaListenerContainerFactoryConfigurer} tarafindan TUM
 * {@code @KafkaListener}'lara otomatik uygulanir (ozel bir factory bean'i tanimlamaya gerek yok —
 * Boot bu bean'i ObjectProvider ile arar ve bulursa configurer'a baglar).
 *
 * <p>Deserialization korumasi (Bolum 3.2, son madde) kod DEGIL, {@code application.yml}'deki {@code
 * spring.kafka.consumer.*deserializer} + {@code ErrorHandlingDeserializer} delegate ayarlariyla
 * saglanir — Boot'un otomatik ConsumerFactory'si bu property'leri zaten okur.
 */
@Configuration
public class KafkaConsumerConfig {

  @Bean
  public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
    var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

    // 1sn -> 2sn -> 4sn -> 8sn, maks. 4 deneme, sonra DLT.
    var backOff = new ExponentialBackOff(1000L, 2.0);
    backOff.setMaxAttempts(4);

    var handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        DeserializationException.class,
        MessageConversionException.class,
        IllegalArgumentException.class,
        BusinessRuleException.class);
    return handler;
  }
}
