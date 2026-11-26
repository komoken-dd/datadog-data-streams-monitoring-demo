package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.event.ContainerStoppedEvent;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

/** Dedicated local test broker only. Never connects to KAFKA_BOOTSTRAP_SERVERS or the demo cluster. */
@EnabledIfEnvironmentVariable(named = "BROKERAGE_TEST_KAFKA_BOOTSTRAP", matches = ".+")
class KafkaFailureRecoveryIntegrationTest {
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void persistentFailureNeverSkipsItsOffsetAndHealthyRestartReplaysItAndTheFollowingRecord() throws Exception {
        String bootstrap = System.getenv("BROKERAGE_TEST_KAFKA_BOOTSTRAP");
        assertThat(bootstrap).as("requires the dedicated loopback test broker")
                .matches("(?:127\\.0\\.0\\.1|localhost):19092");
        String suffix = UUID.randomUUID().toString();
        String topic = "brokerage-security-offset-test-" + suffix;
        String group = "brokerage-security-offset-test-" + suffix;
        var partition = new TopicPartition(topic, 0);
        List<ConcurrentMessageListenerContainer<String, OrderAcceptedEvent>> containers = new ArrayList<>();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrap,
                "request.timeout.ms", "10000", "default.api.timeout.ms", "10000"))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            try {
                produce(bootstrap, topic, "before", "failed", "after");
                List<Long> failures = new CopyOnWriteArrayList<>();
                List<String> completed = new CopyOnWriteArrayList<>();
                // Persistently fail the same event across more than the default
                // handler's ten-attempt recovery threshold. This policy stops on
                // each unexpected failure instead of consuming its retry budget
                // and eventually advancing past it.
                for (int attempt = 0; attempt < 12; attempt++) {
                    var stopped = new CountDownLatch(1);
                    var container = container(bootstrap, topic, group, "failure-" + attempt);
                    containers.add(container);
                    container.setApplicationEventPublisher(event -> {
                        if (event instanceof ContainerStoppedEvent) stopped.countDown();
                    });
                    container.setupMessageListener((MessageListener<String, OrderAcceptedEvent>) record -> {
                        if (record.value().getEventId().equals("failed")) {
                            failures.add(record.offset());
                            throw new IllegalStateException("injected persistent database failure");
                        }
                        completed.add(record.value().getEventId());
                    });
                    container.start();
                    assertThat(stopped.await(15, TimeUnit.SECONDS)).as("failed container stopped, attempt %s", attempt).isTrue();
                    assertThat(container.isRunning()).isFalse();
                    assertThat(committed(admin, group, partition)).as("failed offset remains uncommitted").isEqualTo(1L);
                }
                assertThat(failures).hasSize(12).allMatch(offset -> offset == 1L);
                assertThat(completed).containsExactly("before");

                var replayed = new CopyOnWriteArrayList<String>();
                var replayDone = new CountDownLatch(2);
                var healthy = container(bootstrap, topic, group, "recovered");
                containers.add(healthy);
                healthy.setupMessageListener((MessageListener<String, OrderAcceptedEvent>) record -> {
                    replayed.add(record.value().getEventId());
                    replayDone.countDown();
                });
                healthy.start();
                assertThat(replayDone.await(15, TimeUnit.SECONDS)).as("recovered consumer replays both records").isTrue();
                await(() -> committed(admin, group, partition) == 3L);
                assertThat(replayed).containsExactly("failed", "after");
                assertThat(committed(admin, group, partition)).isEqualTo(3L);
            } finally {
                containers.forEach(container -> container.stop(true));
                admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
                admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static ConcurrentMessageListenerContainer<String, OrderAcceptedEvent> container(
            String bootstrap, String topic, String group, String name) {
        var properties = new KafkaProperties();
        properties.setBootstrapServers(List.of(bootstrap));
        properties.getConsumer().setGroupId(group);
        properties.getConsumer().setAutoOffsetReset("earliest");
        properties.getConsumer().setEnableAutoCommit(false);
        properties.getConsumer().getProperties().put("max.poll.records", "1");
        properties.getConsumer().getProperties().put("group.protocol", "classic");
        properties.getConsumer().getProperties().put("session.timeout.ms", "6000");
        properties.getConsumer().getProperties().put("heartbeat.interval.ms", "1000");
        var factory = KafkaFactory.listenerFactory(properties, OrderAcceptedEvent.class);
        var container = factory.createContainer(topic);
        container.setBeanName(name + "-" + group);
        container.getContainerProperties().setPollTimeout(200);
        container.getContainerProperties().setShutdownTimeout(5000);
        container.getContainerProperties().setSyncCommits(true);
        return container;
    }

    private static void produce(String bootstrap, String topic, String... ids) throws Exception {
        try (var producer = new KafkaProducer<String, byte[]>(Map.of("bootstrap.servers", bootstrap,
                "key.serializer", StringSerializer.class, "value.serializer", ByteArraySerializer.class,
                "acks", "all", "enable.idempotence", true))) {
            var serializer = new AvroSpecificSerializer();
            for (String id : ids) {
                Instant now = Instant.now();
                var event = OrderAcceptedEvent.newBuilder().setEventId(id).setOccurredAt(now)
                        .setAccountId("A00001").setOrderId("order-" + id).setSymbol("D101").setSide("BUY")
                        .setOrderType("MARKET").setQuantity(100).setLimitPrice(null)
                        .setReservedAmountJpy(new BigDecimal("330000")).setAcceptedAt(now).build();
                producer.send(new ProducerRecord<>(topic, "A00001", serializer.serialize(topic, event)))
                        .get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static long committed(Admin admin, String group, TopicPartition partition) {
        try {
            var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(partition);
            return offset == null ? -1L : offset.offset();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not inspect isolated test consumer offsets", exception);
        }
    }

    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Consumer offset did not commit after recovery");
            Thread.sleep(50);
        }
    }
}
