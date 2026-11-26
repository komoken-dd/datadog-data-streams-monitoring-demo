package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.DurableKafkaSender;
import io.github.komokendd.dsm.brokerage.common.JdbcEventOutbox;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@org.springframework.scheduling.annotation.EnableScheduling
@Configuration
class OrderPersistenceConfiguration {
    @Bean
    JdbcEventOutbox orderOutbox(JdbcTemplate jdbc) {
        return new JdbcEventOutbox(jdbc, "order_service");
    }

    @Bean(destroyMethod = "close")
    DurableKafkaSender durableKafkaSender(KafkaProperties properties) {
        return new DurableKafkaSender(properties);
    }
}
