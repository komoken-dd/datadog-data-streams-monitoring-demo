package io.github.komokendd.dsm.fx.risk;

import io.github.komokendd.dsm.fx.common.IdempotentEventStore;
import io.github.komokendd.dsm.fx.common.KafkaFactory;
import io.github.komokendd.dsm.fx.schema.TradeExecutedEvent;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class RiskConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, TradeExecutedEvent> riskExecutedKafkaListenerFactory(
            KafkaProperties properties) {
        return KafkaFactory.listenerFactory(properties, TradeExecutedEvent.class);
    }

    @Bean
    IdempotentEventStore riskEventStore(JdbcTemplate jdbc) {
        return new IdempotentEventStore(jdbc, "risk_service");
    }
}

