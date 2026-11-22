package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.OrderType;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.time.Instant;

/** An order resting on the simulated exchange, as loaded at the start of a matching tick. */
public record WorkingOrder(
        String orderId,
        String eventId,
        String accountId,
        String symbol,
        Side side,
        OrderType orderType,
        long quantity,
        BigDecimal limitPrice,
        BigDecimal reservedAmountJpy,
        String demoScenarioId,
        long cumulativeQuantity,
        int nextExecutionSequence,
        WorkingOrderStatus status,
        Instant acceptedAt,
        Instant firstExecutedAt) {

    public long leavesQuantity() {
        return quantity - cumulativeQuantity;
    }

    /** Market orders always match; limit orders only when the price is at or better than the limit. */
    public boolean marketableAt(BigDecimal price) {
        if (orderType == OrderType.MARKET) {
            return true;
        }
        return side == Side.BUY
                ? limitPrice.compareTo(price) >= 0
                : limitPrice.compareTo(price) <= 0;
    }

    public WorkingOrder apply(TickResult.OrderUpdate update) {
        return new WorkingOrder(
                orderId, eventId, accountId, symbol, side, orderType, quantity, limitPrice, reservedAmountJpy,
                demoScenarioId, update.cumulativeQuantity(), update.nextExecutionSequence(), update.status(),
                acceptedAt, update.firstExecutedAt());
    }
}
