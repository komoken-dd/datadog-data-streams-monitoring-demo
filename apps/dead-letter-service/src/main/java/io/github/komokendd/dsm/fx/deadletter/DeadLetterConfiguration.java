package io.github.komokendd.dsm.fx.deadletter;

import io.github.komokendd.dsm.fx.common.KafkaFactory;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class DeadLetterConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderApprovedEvent> deadLetterKafkaListenerFactory(
            KafkaProperties properties) {
        return KafkaFactory.listenerFactory(properties, OrderApprovedEvent.class);
    }
}

