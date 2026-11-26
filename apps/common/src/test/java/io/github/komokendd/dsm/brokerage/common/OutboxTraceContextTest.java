package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

class OutboxTraceContextTest {
    private static final SpanContext ORIGINAL = SpanContext.create(
            "11111111111111111111111111111111", "2222222222222222", TraceFlags.getSampled(),
            TraceState.builder().put("vendor", "opaque-trace-state").build());

    @Test
    void restoresOriginalParentWithoutLeakingBaggageOrTheRelayContext() {
        OutboxTraceContext captured;
        var context = Baggage.builder().put("private-customer-value", "never-persist")
                .build().storeInContext(Context.root().with(Span.wrap(ORIGINAL)));
        try (var scope = context.makeCurrent()) {
            captured = OutboxTraceContext.capture();
        }
        assertThat(captured.traceparent()).isEqualTo("00-11111111111111111111111111111111-2222222222222222-01");
        assertThat(captured.tracestate()).isEqualTo("vendor=opaque-trace-state");
        var decoy = SpanContext.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb",
                TraceFlags.getDefault(), TraceState.getDefault());
        try (var scope = context.with(Span.wrap(decoy)).makeCurrent()) {
            var restored = captured.restore();
            var parent = Span.fromContext(restored).getSpanContext();
            assertThat(parent.getTraceId()).isEqualTo(ORIGINAL.getTraceId());
            assertThat(parent.getSpanId()).isEqualTo(ORIGINAL.getSpanId());
            assertThat(parent.isSampled()).isTrue();
            assertThat(parent.isRemote()).isTrue();
            assertThat(Baggage.fromContext(restored).isEmpty()).isTrue();
        }
    }

    @Test
    void legacyAndMalformedRowsStartWithoutAnUnrelatedParent() {
        try (var scope = Context.root().with(Span.wrap(ORIGINAL)).makeCurrent()) {
            assertThat(Span.fromContext(new OutboxTraceContext(null, null).restore()).getSpanContext().isValid()).isFalse();
            assertThat(Span.fromContext(new OutboxTraceContext("invalid", "vendor=x").restore()).getSpanContext().isValid()).isFalse();
        }
    }

    @Test
    void dropsOversizedVendorStateWithoutFailingTheFinancialAppend() {
        var state = TraceState.builder().put("vendor1", "x".repeat(200))
                .put("vendor2", "y".repeat(200)).put("vendor3", "z".repeat(200)).build();
        var original = SpanContext.create(ORIGINAL.getTraceId(), ORIGINAL.getSpanId(), TraceFlags.getSampled(), state);
        try (var scope = Context.root().with(Span.wrap(original)).makeCurrent()) {
            var captured = OutboxTraceContext.capture();
            assertThat(captured.traceparent()).isNotNull();
            assertThat(captured.tracestate()).isNull();
        }
    }

    @Test
    void captureWithoutAnActiveSpanStoresNoHeaders() {
        try (var scope = Context.root().makeCurrent()) {
            assertThat(OutboxTraceContext.capture()).isEqualTo(new OutboxTraceContext(null, null));
        }
    }
}
