package io.github.komokendd.dsm.fx.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.fx.schema.OrderReceivedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.listener.ContainerProperties;

class KafkaFactoryTest {
    @Test
    void createsRecordAcknowledgingObservedListenerFactory() {
        var factory = KafkaFactory.listenerFactory(new KafkaProperties(), OrderReceivedEvent.class);

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getContainerProperties().isObservationEnabled()).isTrue();
    }
}
