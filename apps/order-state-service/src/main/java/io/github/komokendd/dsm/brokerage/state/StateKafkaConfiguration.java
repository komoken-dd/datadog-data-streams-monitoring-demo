package io.github.komokendd.dsm.brokerage.state;

import io.github.komokendd.dsm.brokerage.common.KafkaFactory;
import io.github.komokendd.dsm.brokerage.schema.BuyingPowerUpdatedEvent;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationDeliveredEvent;
import io.github.komokendd.dsm.brokerage.schema.NotificationRequest;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import io.github.komokendd.dsm.brokerage.schema.TradeReportIssuedEvent;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

@Configuration
class StateKafkaConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderAcceptedEvent> stateAcceptedFactory(KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, OrderAcceptedEvent.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, ExecutionReportEvent> stateExecutionFactory(KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, ExecutionReportEvent.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, OrderExpiredEvent> stateExpiredFactory(KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, OrderExpiredEvent.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, BuyingPowerUpdatedEvent> stateBuyingPowerFactory(
            KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, BuyingPowerUpdatedEvent.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, TradeReportIssuedEvent> stateTradeReportFactory(
            KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, TradeReportIssuedEvent.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, NotificationDeliveredEvent> stateDeliveredFactory(
            KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, NotificationDeliveredEvent.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, NotificationRequest> stateNotificationRequestFactory(
            KafkaProperties p) {
        return KafkaFactory.listenerFactory(p, NotificationRequest.class);
    }
}
