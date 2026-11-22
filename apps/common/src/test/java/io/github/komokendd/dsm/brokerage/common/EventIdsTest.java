package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EventIdsTest {
    @Test
    void derivesStableIdsForRedeliveredEvents() {
        String first = EventIds.derived("source-event-1", "TRADE_EXECUTED");
        String second = EventIds.derived("source-event-1", "TRADE_EXECUTED");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void separatesDifferentEventTypes() {
        assertThat(EventIds.derived("source-event-1", "ORDER_APPROVED"))
                .isNotEqualTo(EventIds.derived("source-event-1", "ORDER_REJECTED"));
    }

    @Test
    void rejectsBlankInputs() {
        assertThatThrownBy(() -> EventIds.derived(" ", "TRADE_EXECUTED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
