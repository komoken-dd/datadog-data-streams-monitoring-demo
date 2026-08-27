package io.github.komokendd.dsm.fx.settlement;

import io.github.komokendd.dsm.fx.common.IdempotentEventStore;
import io.github.komokendd.dsm.fx.common.KafkaFactory;
import io.github.komokendd.dsm.fx.schema.TradeConfirmedEvent;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class SettlementConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, TradeConfirmedEvent> confirmedKafkaListenerFactory(
            KafkaProperties properties) {
        return KafkaFactory.listenerFactory(properties, TradeConfirmedEvent.class);
    }

    @Bean
    IdempotentEventStore settlementEventStore(JdbcTemplate jdbc) {
        return new IdempotentEventStore(jdbc, "settlement_service");
    }
}

