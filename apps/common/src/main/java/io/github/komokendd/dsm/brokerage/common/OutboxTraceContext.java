package io.github.komokendd.dsm.brokerage.common;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.List;

/** A bounded trace-only carrier: deliberately excludes baggage and arbitrary request headers. */
record OutboxTraceContext(String traceparent, String tracestate) {
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<OutboxTraceContext> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(OutboxTraceContext carrier) {
            return List.of("traceparent", "tracestate");
        }
        @Override public String get(OutboxTraceContext carrier, String key) {
            return switch (key) {
                case "traceparent" -> carrier.traceparent();
                case "tracestate" -> carrier.tracestate();
                default -> null;
            };
        }
    };

    static OutboxTraceContext capture() {
        var carrier = new HashMap<String, String>();
        PROPAGATOR.inject(Context.current(), carrier, (map, key, value) -> map.put(key, value));
        return new OutboxTraceContext(bounded(carrier.get("traceparent"), 128),
                bounded(carrier.get("tracestate"), 512));
    }

    Context restore() {
        // Root prevents the relay scheduler's context (or any baggage) becoming
        // the causal parent. Old rows without trace context start a fresh trace.
        return PROPAGATOR.extract(Context.root(), this, GETTER);
    }

    private static String bounded(String value, int maxLength) {
        // Telemetry must never make a financial transaction fail.
        return value == null || value.length() > maxLength ? null : value;
    }
}
