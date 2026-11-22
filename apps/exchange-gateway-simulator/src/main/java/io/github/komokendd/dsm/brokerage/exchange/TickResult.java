package io.github.komokendd.dsm.brokerage.exchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Output of one matching tick: fills and expiries to publish, and the order-book rows to update. */
public record TickResult(List<Fill> fills, List<Expiry> expiries, List<OrderUpdate> updates) {
    public static final String PARTIALLY_FILLED = "PARTIALLY_FILLED";
    public static final String FILLED = "FILLED";
    public static final String LIMIT_DOWN_UNFILLED = "LIMIT_DOWN_UNFILLED";
    public static final String SESSION_CLOSE = "SESSION_CLOSE";

    public boolean isEmpty() {
        return fills.isEmpty() && expiries.isEmpty() && updates.isEmpty();
    }

    /**
     * One execution. Quantities after this fill; {@code firstExecution} marks the order's first fill.
     */
    public record Fill(
            WorkingOrder order,
            int sequence,
            long executedQuantity,
            BigDecimal price,
            long cumulativeQuantity,
            long leavesQuantity,
            String orderStatus,
            Instant executedAt,
            boolean firstExecution,
            boolean openingAuction) {

        public boolean finalFill() {
            return leavesQuantity == 0;
        }
    }

    public record Expiry(
            WorkingOrder order,
            long cumulativeQuantity,
            long expiredQuantity,
            String reason,
            Instant expiredAt) {
    }

    public record OrderUpdate(
            String orderId,
            long cumulativeQuantity,
            int nextExecutionSequence,
            WorkingOrderStatus status,
            Instant firstExecutedAt) {
    }
}
