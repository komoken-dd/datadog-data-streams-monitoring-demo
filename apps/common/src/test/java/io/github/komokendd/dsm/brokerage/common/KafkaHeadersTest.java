package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class KafkaHeadersTest {
    @Test
    void copiesOnlyTheLatestApplicationOwnedRetryMetadata() {
        var source = new RecordHeaders()
                .add(KafkaHeaders.RETRY_COUNT, bytes("1"))
                .add(KafkaHeaders.RETRY_COUNT, bytes("2"))
                .add(KafkaHeaders.FAILURE_CATEGORY, bytes("timeout"))
                .add(KafkaHeaders.FAILURE_TIMESTAMP, bytes("2026-08-28T00:00:00Z"))
                .add(KafkaHeaders.ORIGINATING_TOPIC, bytes("brokerage.notifications.retry.5s.v1"))
                .add(KafkaHeaders.REDRIVEN, bytes("false"))
                .add("dd-pathway-ctx", bytes("old-pathway"))
                .add("x-datadog-trace-id", bytes("old-trace"))
                .add("traceparent", bytes("old-traceparent"))
                .add("tracestate", bytes("old-tracestate"));

        var copied = KafkaHeaders.copyDemoMetadata(source);

        assertThat(names(copied)).containsExactlyInAnyOrder(
                KafkaHeaders.RETRY_COUNT,
                KafkaHeaders.FAILURE_CATEGORY,
                KafkaHeaders.FAILURE_TIMESTAMP,
                KafkaHeaders.ORIGINATING_TOPIC,
                KafkaHeaders.REDRIVEN);
        assertThat(text(copied.lastHeader(KafkaHeaders.RETRY_COUNT))).isEqualTo("2");
        assertThat(copied.lastHeader("dd-pathway-ctx")).isNull();
        assertThat(copied.lastHeader("x-datadog-trace-id")).isNull();
        assertThat(copied.lastHeader("traceparent")).isNull();
        assertThat(copied.lastHeader("tracestate")).isNull();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Header header) {
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Iterable<String> names(Iterable<Header> headers) {
        return StreamSupport.stream(headers.spliterator(), false).map(Header::key).toList();
    }
}
