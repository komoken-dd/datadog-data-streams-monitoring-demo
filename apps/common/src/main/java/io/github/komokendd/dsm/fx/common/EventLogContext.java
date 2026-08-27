package io.github.komokendd.dsm.fx.common;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

public final class EventLogContext implements AutoCloseable {
    private final Set<String> keys = new HashSet<>();

    private EventLogContext(Map<String, ?> values) {
        values.forEach(this::put);
    }

    public static EventLogContext open(
            String orderId,
            String eventId,
            String eventType,
            String currencyPair,
            String scenarioId) {
        var activeSpan = Span.current();
        if (activeSpan.getSpanContext().isValid()) {
            activeSpan.setAttribute("order_id", orderId);
            activeSpan.setAttribute("event_type", eventType);
            if (scenarioId != null) {
                activeSpan.setAttribute("demo_scenario_id", scenarioId);
            }
        }
        return new EventLogContext(Map.of(
                "order_id", orderId,
                "event_id", eventId,
                "event_type", eventType,
                "currency_pair", currencyPair,
                "demo_scenario_id", scenarioId == null ? "" : scenarioId));
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

    @Override
    public void close() {
        keys.forEach(MDC::remove);
    }
}
