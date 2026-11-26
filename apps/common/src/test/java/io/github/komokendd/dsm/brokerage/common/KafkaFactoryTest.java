package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaFactoryTest {
    @Test
    void createsRecordAcknowledgingObservedListenerFactory() {
        var factory = KafkaFactory.listenerFactory(new KafkaProperties(), OrderAcceptedEvent.class);

        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getContainerProperties().isObservationEnabled()).isTrue();
        assertThat(factory.createContainer("business-topic").getCommonErrorHandler())
                .isInstanceOf(CommonContainerStoppingErrorHandler.class);
    }
    @Test
    void processingFailureStopsWithoutCommittingOrSkippingTheFailedOffset() {
        var handler = new CommonContainerStoppingErrorHandler(Runnable::run);
        var container = mock(MessageListenerContainer.class);
        var consumer = mock(Consumer.class);
        when(container.isRunning()).thenReturn(false);
        assertThatThrownBy(() -> handler.handleRemaining(new IllegalStateException("database unavailable"),
                List.of(new ConsumerRecord<>("business-topic", 0, 42L, "A00001", "record")), consumer, container))
                .isInstanceOf(org.springframework.kafka.KafkaException.class);
        verify(container).stopAbnormally(any(Runnable.class));
        verifyNoInteractions(consumer);
    }
}
