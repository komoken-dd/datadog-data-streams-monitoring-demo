package io.github.komokendd.dsm.brokerage.common;

import java.util.HashMap;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;

public final class KafkaFactory {
    private KafkaFactory() {
    }

    public static <T extends SpecificRecord> ConcurrentKafkaListenerContainerFactory<String, T> listenerFactory(
            KafkaProperties properties, Class<T> valueClass) {
        Map<String, Object> consumer = new HashMap<>(properties.buildConsumerProperties());
        consumer.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumer,
                new StringDeserializer(),
                new AvroSpecificDeserializer<>(valueClass));
        var factory = new ConcurrentKafkaListenerContainerFactory<String, T>();
        factory.setConsumerFactory(consumerFactory);
        // A business event is never recovered by merely logging and committing its offset.
        // Unexpected processing/deserialization failures stop the affected container for
        // investigation; restarting replays the unprocessed record. Notification retry
        // topics continue to be handled explicitly by their listener implementation.
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
