package io.github.komokendd.dsm.brokerage.holdings;

import io.github.komokendd.dsm.brokerage.common.IdempotentEventStore;
import io.github.komokendd.dsm.brokerage.common.KafkaFactory;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class HoldingsConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ExecutionReportEvent> executionListenerFactory(
            KafkaProperties properties) {
        return KafkaFactory.listenerFactory(properties, ExecutionReportEvent.class);
    }

    @Bean
    IdempotentEventStore holdingsEventStore(JdbcTemplate jdbc) {
        return new IdempotentEventStore(jdbc, "holdings_service");
    }
}
