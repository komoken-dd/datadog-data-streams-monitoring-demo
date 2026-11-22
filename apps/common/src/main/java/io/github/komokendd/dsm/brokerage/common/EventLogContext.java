package io.github.komokendd.dsm.brokerage.common;

import io.opentelemetry.api.trace.Span;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;

/**
 * Adds business identifiers to the structured log line and the active span.
 * High-cardinality identifiers stay here, never in metric tags.
 */
public final class EventLogContext implements AutoCloseable {
    private final Set<String> keys = new HashSet<>();

    private EventLogContext(Map<String, ?> values) {
        values.forEach(this::put);
    }

    public static EventLogContext open(
            String accountId,
            String orderId,
            String eventId,
            String eventType,
            String symbol,
            String scenarioId) {
        var activeSpan = Span.current();
        if (activeSpan.getSpanContext().isValid()) {
            setIfPresent(activeSpan, "account_id", accountId);
            setIfPresent(activeSpan, "order_id", orderId);
            setIfPresent(activeSpan, "event_type", eventType);
            setIfPresent(activeSpan, "symbol", symbol);
            setIfPresent(activeSpan, "demo_scenario_id", scenarioId);
        }
        Map<String, Object> values = new HashMap<>();
        values.put("account_id", accountId);
        values.put("order_id", orderId);
        values.put("event_id", eventId);
        values.put("event_type", eventType);
        values.put("symbol", symbol);
        values.put("demo_scenario_id", scenarioId == null ? "" : scenarioId);
        return new EventLogContext(values);
    }

    public EventLogContext retry(int retryCount) {
        put("retry_count", retryCount);
        return this;
    }

    public EventLogContext attribute(String key, Object value) {
        put(key, value);
        return this;
    }

    private void put(String key, Object value) {
        if (value != null) {
            MDC.put(key, value.toString());
            keys.add(key);
        }
    }

    private static void setIfPresent(Span span, String key, String value) {
        if (value != null && !value.isEmpty()) {
            span.setAttribute(key, value);
        }
    }

    @Override
    public void close() {
        keys.forEach(MDC::remove);
    }
}
