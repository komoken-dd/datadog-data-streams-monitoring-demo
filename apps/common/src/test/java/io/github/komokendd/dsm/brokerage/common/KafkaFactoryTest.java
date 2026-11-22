package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.listener.ContainerProperties;

class KafkaFactoryTest {
    @Test
    void createsRecordAcknowledgingObservedListenerFactory() {
        var factory = KafkaFactory.listenerFactory(new KafkaProperties(), OrderAcceptedEvent.class);

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getContainerProperties().isObservationEnabled()).isTrue();
    }
}
