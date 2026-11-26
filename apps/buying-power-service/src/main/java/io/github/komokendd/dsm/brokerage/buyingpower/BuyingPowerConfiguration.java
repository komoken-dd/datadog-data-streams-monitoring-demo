package io.github.komokendd.dsm.brokerage.buyingpower;

import io.github.komokendd.dsm.brokerage.common.IdempotentEventStore;
import io.github.komokendd.dsm.brokerage.common.KafkaFactory;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class BuyingPowerConfiguration {
    private static final int LISTENER_CONCURRENCY = 3;

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdempotentEventStore idempotentEventStore(JdbcTemplate jdbc) {
        return new IdempotentEventStore(jdbc, BuyingPowerRepository.SCHEMA);
    }

    @Bean
    ReflectionControl reflectionControl(
            @Value("${demo.buying-power.normal-reflection-delay-ms:0}") long normalDelayMillis,
            @Value("${demo.buying-power.slow-reflection-delay-ms:2500}") long slowDelayMillis,
            Clock clock) {
        return new ReflectionControl(normalDelayMillis, slowDelayMillis, clock);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ExecutionReportEvent> executionListenerFactory(
            KafkaProperties properties) {
        var factory = KafkaFactory.listenerFactory(properties, ExecutionReportEvent.class);
        factory.setConcurrency(LISTENER_CONCURRENCY);
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderExpiredEvent> expiryListenerFactory(
            KafkaProperties properties) {
        var factory = KafkaFactory.listenerFactory(properties, OrderExpiredEvent.class);
        factory.setConcurrency(LISTENER_CONCURRENCY);
        return factory;
    }
}
