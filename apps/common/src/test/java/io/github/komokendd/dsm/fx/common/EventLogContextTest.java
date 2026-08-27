package io.github.komokendd.dsm.fx.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class EventLogContextTest {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void addsAndRemovesStandardAndCustomLogAttributes() {
        try (var context = EventLogContext.open(
                        "order-1", "event-1", "DEAD_LETTER", "USD/JPY", "scenario-1")
                .retry(3)
                .attribute("failure_category", "DEMO_PROCESSING_FAILURE")
                .attribute("notional_usd", "1000000.00")) {
            assertThat(MDC.get("order_id")).isEqualTo("order-1");
            assertThat(MDC.get("retry_count")).isEqualTo("3");
            assertThat(MDC.get("failure_category")).isEqualTo("DEMO_PROCESSING_FAILURE");
            assertThat(MDC.get("notional_usd")).isEqualTo("1000000.00");
        }

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
