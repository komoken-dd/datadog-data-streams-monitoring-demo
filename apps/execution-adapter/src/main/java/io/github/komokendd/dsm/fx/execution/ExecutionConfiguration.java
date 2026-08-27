package io.github.komokendd.dsm.fx.execution;

import io.github.komokendd.dsm.fx.common.KafkaFactory;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class ExecutionConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderApprovedEvent> approvedKafkaListenerFactory(
            KafkaProperties properties) {
        var factory = KafkaFactory.listenerFactory(properties, OrderApprovedEvent.class);
        factory.setConcurrency(3);
        return factory;
    }
}
