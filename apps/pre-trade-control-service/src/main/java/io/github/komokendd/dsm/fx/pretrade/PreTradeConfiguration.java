package io.github.komokendd.dsm.fx.pretrade;

import io.github.komokendd.dsm.fx.common.KafkaFactory;
import io.github.komokendd.dsm.fx.schema.OrderReceivedEvent;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class PreTradeConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderReceivedEvent> receivedKafkaListenerFactory(
            KafkaProperties properties) {
        return KafkaFactory.listenerFactory(properties, OrderReceivedEvent.class);
    }
}

