package io.github.komokendd.dsm.fx.state;

import io.github.komokendd.dsm.fx.common.KafkaFactory;
import io.github.komokendd.dsm.fx.schema.OrderApprovedEvent;
import io.github.komokendd.dsm.fx.schema.OrderReceivedEvent;
import io.github.komokendd.dsm.fx.schema.OrderRejectedEvent;
import io.github.komokendd.dsm.fx.schema.SettlementInstructedEvent;
import io.github.komokendd.dsm.fx.schema.TradeConfirmedEvent;
import io.github.komokendd.dsm.fx.schema.TradeExecutedEvent;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class StateKafkaConfiguration {
    @Bean ConcurrentKafkaListenerContainerFactory<String, OrderReceivedEvent> stateReceivedFactory(KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, OrderReceivedEvent.class);
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String, OrderApprovedEvent> stateApprovedFactory(KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, OrderApprovedEvent.class);
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String, OrderRejectedEvent> stateRejectedFactory(KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, OrderRejectedEvent.class);
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String, TradeExecutedEvent> stateExecutedFactory(KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, TradeExecutedEvent.class);
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String, TradeConfirmedEvent> stateConfirmedFactory(KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, TradeConfirmedEvent.class);
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String, SettlementInstructedEvent> stateSettlementFactory(KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, SettlementInstructedEvent.class);
    }
}

