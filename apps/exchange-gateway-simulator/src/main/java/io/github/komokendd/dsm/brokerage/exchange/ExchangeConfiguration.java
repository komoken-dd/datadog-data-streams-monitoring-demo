package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.KafkaFactory;
import io.github.komokendd.dsm.brokerage.common.JdbcEventOutbox;
import io.github.komokendd.dsm.brokerage.common.DurableKafkaSender;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import java.time.Clock;
import java.util.Random;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
@EnableConfigurationProperties(ExchangeProperties.class)
class ExchangeConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderAcceptedEvent> acceptedOrderListenerFactory(
            KafkaProperties properties) {
        return KafkaFactory.listenerFactory(properties, OrderAcceptedEvent.class);
    }

    @Bean
    MatchingEngine matchingEngine(ExchangeProperties properties) {
        return new MatchingEngine(new Random(properties.seed()), properties.matchingSettings());
    }

    @Bean
    JdbcEventOutbox exchangeOutbox(JdbcTemplate jdbc) {
        return new JdbcEventOutbox(jdbc, "exchange_gateway");
    }

    @Bean(destroyMethod = "close")
    DurableKafkaSender durableKafkaSender(KafkaProperties properties) {
        return new DurableKafkaSender(properties);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
