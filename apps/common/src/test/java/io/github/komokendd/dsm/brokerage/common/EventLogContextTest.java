package io.github.komokendd.dsm.brokerage.common;

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
                        "A00001", "order-1", "event-1", "DEAD_LETTER", "D105", "scenario-1")
                .retry(3)
                .attribute("failure_category", "PUSH_PROVIDER_UNAVAILABLE")
                .attribute("execution_amount_jpy", "1250000")) {
            assertThat(MDC.get("account_id")).isEqualTo("A00001");
            assertThat(MDC.get("order_id")).isEqualTo("order-1");
            assertThat(MDC.get("symbol")).isEqualTo("D105");
            assertThat(MDC.get("retry_count")).isEqualTo("3");
            assertThat(MDC.get("failure_category")).isEqualTo("PUSH_PROVIDER_UNAVAILABLE");
            assertThat(MDC.get("execution_amount_jpy")).isEqualTo("1250000");
        }

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
