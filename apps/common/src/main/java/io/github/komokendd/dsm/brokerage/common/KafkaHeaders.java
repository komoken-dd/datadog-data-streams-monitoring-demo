package io.github.komokendd.dsm.brokerage.common;

import java.util.List;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

public final class KafkaHeaders {
    public static final String RETRY_COUNT = "brokerage-retry-count";
    public static final String FAILURE_CATEGORY = "brokerage-failure-category";
    public static final String FAILURE_TIMESTAMP = "brokerage-failure-timestamp";
    public static final String ORIGINATING_TOPIC = "brokerage-originating-topic";
    public static final String REDRIVEN = "brokerage-redriven";
    private static final List<String> DEMO_METADATA = List.of(
            RETRY_COUNT,
            FAILURE_CATEGORY,
            FAILURE_TIMESTAMP,
            ORIGINATING_TOPIC,
            REDRIVEN);

    private KafkaHeaders() {
    }

    /**
     * Copies only application-owned retry metadata to a new record. Trace and
     * DSM propagation headers must be created by the active producer span.
     */
    public static Headers copyDemoMetadata(Headers source) {
        Headers target = new RecordHeaders();
        for (String name : DEMO_METADATA) {
            var header = source.lastHeader(name);
            if (header != null) {
                target.add(name, header.value().clone());
            }
        }
        return target;
    }
}
