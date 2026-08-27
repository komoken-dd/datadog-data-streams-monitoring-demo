package io.github.komokendd.dsm.fx.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {
    @Test
    void supportsRetryDeadLetterRedriveAndCompletion() {
        assertThat(OrderStatus.RETRYING.mayFollow(OrderStatus.PENDING_EXECUTION)).isTrue();
        assertThat(OrderStatus.DEAD_LETTER.mayFollow(OrderStatus.RETRYING)).isTrue();
        assertThat(OrderStatus.REDRIVEN.mayFollow(OrderStatus.DEAD_LETTER)).isTrue();
        assertThat(OrderStatus.EXECUTED.mayFollow(OrderStatus.REDRIVEN)).isTrue();
    }

    @Test
    void rejectsRegressionAndTerminalChanges() {
        assertThat(OrderStatus.RECEIVED.mayFollow(OrderStatus.EXECUTED)).isFalse();
        assertThat(OrderStatus.EXECUTED.mayFollow(OrderStatus.REJECTED)).isFalse();
    }
}
