package io.github.komokendd.dsm.fx.confirmation;

import io.github.komokendd.dsm.fx.common.IdempotentEventStore;
import io.github.komokendd.dsm.fx.common.KafkaFactory;
import io.github.komokendd.dsm.fx.schema.TradeExecutedEvent;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class ConfirmationConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, TradeExecutedEvent> confirmationKafkaListenerFactory(
            KafkaProperties properties) {
        return KafkaFactory.listenerFactory(properties, TradeExecutedEvent.class);
    }

    @Bean
    IdempotentEventStore confirmationEventStore(JdbcTemplate jdbc) {
        return new IdempotentEventStore(jdbc, "confirmation_service");
    }
}

