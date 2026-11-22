package io.github.komokendd.dsm.brokerage.state;

import java.time.Instant;

/**
 * Projected order row. Every order lifecycle event (accepted, execution, expiry) carries
 * all of these fields, so whichever event arrives first can create the row.
 */
record OrderSnapshot(
        String orderId,
        String accountId,
        String symbol,
        String side,
        String orderType,
        long quantity,
        long cumulativeQuantity,
        OrderStatus status,
        Instant acceptedAt,
        String demoScenarioId) {

    /** Merges an incoming event view into the current row; the result never regresses. */
    static OrderSnapshot merge(OrderSnapshot current, OrderSnapshot incoming) {
        if (current == null) {
            return incoming.withStatus(incoming.status.withFillProgress(incoming.cumulativeQuantity, incoming.quantity));
        }
        long cumulative = Math.max(current.cumulativeQuantity, incoming.cumulativeQuantity);
        OrderStatus status = OrderStatus.resolve(current.status, incoming.status)
                .withFillProgress(cumulative, current.quantity);
        return new OrderSnapshot(
                current.orderId,
                current.accountId,
                current.symbol,
                current.side,
                current.orderType,
                current.quantity,
                cumulative,
                status,
                current.acceptedAt,
                current.demoScenarioId != null ? current.demoScenarioId : incoming.demoScenarioId);
    }

    long unfilledQuantity() {
        return Math.max(0, quantity - cumulativeQuantity);
    }

    private OrderSnapshot withStatus(OrderStatus next) {
        return new OrderSnapshot(orderId, accountId, symbol, side, orderType, quantity, cumulativeQuantity,
                next, acceptedAt, demoScenarioId);
    }
}
