package io.github.komokendd.dsm.brokerage.exception;

import io.github.komokendd.dsm.brokerage.common.KafkaFactory;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import java.time.Clock;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class ExceptionConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, NotificationRequest> deadLetterListenerFactory(
            KafkaProperties properties) {
        return KafkaFactory.listenerFactory(properties, NotificationRequest.class);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
