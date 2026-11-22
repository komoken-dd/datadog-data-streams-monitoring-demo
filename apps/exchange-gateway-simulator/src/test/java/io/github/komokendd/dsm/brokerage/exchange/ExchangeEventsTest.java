package io.github.komokendd.dsm.brokerage.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExchangeEventsTest {
    private static final Instant ACCEPTED = Instant.parse("2026-11-24T00:00:00Z");
    private static final Instant EXECUTED = ACCEPTED.plusSeconds(2);

    @Test
    void executionReportCarriesOrderFieldsAndDeterministicIds() {
        WorkingOrder order = Orders.limit("o1", "D104", Side.BUY, 1_000, new BigDecimal("4205"), ACCEPTED);
        var fill = new TickResult.Fill(order, 2, 300, new BigDecimal("4200"), 700, 300,
                TickResult.PARTIALLY_FILLED, EXECUTED, false, false);

        var event = ExchangeEvents.executionReport(fill);

        assertThat(event.getEventId()).isEqualTo(ExchangeEvents.executionReport(fill).getEventId());
        assertThat(event.getEventId()).isNotEqualTo(ExchangeEvents.executionEventId(order, 3));
        assertThat(event.getExecutionId()).isNotEqualTo(event.getEventId());
        assertThat(event.getEventType()).isEqualTo("EXECUTION_REPORTED");
        assertThat(event.getExecutionSequence()).isEqualTo(2);
        assertThat(event.getExecutedQuantity()).isEqualTo(300);
        assertThat(event.getCumulativeQuantity()).isEqualTo(700);
        assertThat(event.getLeavesQuantity()).isEqualTo(300);
        assertThat(event.getOrderStatus()).isEqualTo("PARTIALLY_FILLED");
        assertThat(event.getExecutionPrice()).isEqualTo(new BigDecimal("4200.0"));
        assertThat(event.getExecutionAmountJpy()).isEqualTo(new BigDecimal("1260000"));
        assertThat(event.getLimitPrice()).isEqualTo(new BigDecimal("4205.0"));
        assertThat(event.getReservedAmountJpy()).isEqualTo(new BigDecimal("4205000"));
        assertThat(event.getAcceptedAt()).isEqualTo(ACCEPTED);
        assertThat(event.getExecutedAt()).isEqualTo(EXECUTED);
    }

    @Test
    void expiryReportsUnfilledRemainder() {
        WorkingOrder order = Orders.market("o2", "D105", Side.SELL, 800, ACCEPTED);
        var expiry = new TickResult.Expiry(order, 300, 500, TickResult.LIMIT_DOWN_UNFILLED, EXECUTED);

        var event = ExchangeEvents.orderExpired(expiry);

        assertThat(event.getEventId()).isEqualTo(ExchangeEvents.expiryEventId(order));
        assertThat(event.getExpiredQuantity()).isEqualTo(500);
        assertThat(event.getCumulativeQuantity()).isEqualTo(300);
        assertThat(event.getReason()).isEqualTo("LIMIT_DOWN_UNFILLED");
        assertThat(event.getLimitPrice()).isNull();
        assertThat(event.getReservedAmountJpy()).isEqualTo(new BigDecimal("0"));
    }
}
