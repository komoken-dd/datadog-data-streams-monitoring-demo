package io.github.komokendd.dsm.brokerage.order;

import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
final class KafkaOrderEventPublisher implements OrderEventPublisher {
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, OrderAcceptedEvent> kafka;

    KafkaOrderEventPublisher(KafkaTemplate<String, OrderAcceptedEvent> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(OrderAcceptedEvent event) {
        try {
            kafka.send(new ProducerRecord<>(Topics.ORDERS_ACCEPTED, event.getAccountId(), event))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing the accepted order", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Accepted order could not be published", exception);
        }
    }
}
