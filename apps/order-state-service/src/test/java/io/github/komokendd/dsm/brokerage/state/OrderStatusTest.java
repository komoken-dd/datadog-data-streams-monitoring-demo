package io.github.komokendd.dsm.brokerage.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {
    @Test
    void ranksLifecycleForward() {
        assertThat(OrderStatus.ACCEPTED.rank()).isLessThan(OrderStatus.PARTIALLY_FILLED.rank());
        assertThat(OrderStatus.PARTIALLY_FILLED.rank()).isLessThan(OrderStatus.FILLED.rank());
        assertThat(OrderStatus.FILLED.rank()).isEqualTo(OrderStatus.EXPIRED.rank());
        assertThat(OrderStatus.resolve(null, OrderStatus.ACCEPTED)).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(OrderStatus.resolve(OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED))
                .isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(OrderStatus.resolve(OrderStatus.PARTIALLY_FILLED, OrderStatus.EXPIRED))
                .isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void neverRegressesToLowerRank() {
        assertThat(OrderStatus.resolve(OrderStatus.PARTIALLY_FILLED, OrderStatus.ACCEPTED))
                .isEqualTo(OrderStatus.PARTIALLY_FILLED);
    }

    @Test
    void terminalStatesNeverChange() {
        for (OrderStatus terminal : new OrderStatus[] {OrderStatus.FILLED, OrderStatus.EXPIRED}) {
            for (OrderStatus incoming : OrderStatus.values()) {
                assertThat(OrderStatus.resolve(terminal, incoming)).isEqualTo(terminal);
            }
        }
        assertThat(OrderStatus.EXPIRED.withFillProgress(200, 200)).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void fillProgressPromotesWorkingStatuses() {
        assertThat(OrderStatus.ACCEPTED.withFillProgress(0, 300)).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(OrderStatus.ACCEPTED.withFillProgress(100, 300)).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(OrderStatus.PARTIALLY_FILLED.withFillProgress(300, 300)).isEqualTo(OrderStatus.FILLED);
        assertThat(OrderStatus.WORKING).containsExactly(OrderStatus.ACCEPTED, OrderStatus.PARTIALLY_FILLED);
    }
}
