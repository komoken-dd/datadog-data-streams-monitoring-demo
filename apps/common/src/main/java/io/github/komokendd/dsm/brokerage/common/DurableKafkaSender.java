package io.github.komokendd.dsm.brokerage.common;

import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** Sends the exact bytes committed to a local outbox, including after application upgrades. */
public final class DurableKafkaSender implements AutoCloseable {
    private final DefaultKafkaProducerFactory<String, byte[]> factory;
    private final KafkaTemplate<String, byte[]> template;

    public DurableKafkaSender(KafkaProperties properties) {
        var config = new HashMap<>(properties.buildProducerProperties());
        config.put("acks", "all");
        config.put("enable.idempotence", true);
        factory = new DefaultKafkaProducerFactory<>(config, new StringSerializer(), new ByteArraySerializer());
        template = new KafkaTemplate<>(factory);
        template.setObservationEnabled(true);
    }

    public void send(String topic, String key, byte[] payload) {
        try {
            template.send(topic, key, payload).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Outbox publication interrupted; outcome remains uncertain", exception);
        } catch (ExecutionException | TimeoutException exception) {
            // A timeout cannot prove the broker rejected the record. The committed
            // immutable outbox row remains available for a duplicate-safe replay.
            throw new IllegalStateException("Outbox publication unconfirmed", exception);
        }
    }

    @Override
    public void close() {
        factory.destroy();
    }
}
