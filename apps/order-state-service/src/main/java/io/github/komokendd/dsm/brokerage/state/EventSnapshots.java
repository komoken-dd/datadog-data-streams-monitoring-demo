package io.github.komokendd.dsm.brokerage.state;

import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;

/** Maps lifecycle events to the order view each one implies. */
final class EventSnapshots {
    private EventSnapshots() {
    }

    static OrderSnapshot accepted(OrderAcceptedEvent event) {
        return new OrderSnapshot(event.getOrderId(), event.getAccountId(), event.getSymbol(), event.getSide(),
                event.getOrderType(), event.getQuantity(), 0, OrderStatus.ACCEPTED, event.getAcceptedAt(),
                event.getDemoScenarioId());
    }

    static OrderSnapshot execution(ExecutionReportEvent event) {
        OrderStatus status;
        try {
            status = OrderStatus.parse(event.getOrderStatus());
        } catch (IllegalArgumentException ex) {
            status = OrderStatus.PARTIALLY_FILLED;
        }
        return new OrderSnapshot(event.getOrderId(), event.getAccountId(), event.getSymbol(), event.getSide(),
                event.getOrderType(), event.getQuantity(), event.getCumulativeQuantity(), status,
                event.getAcceptedAt(), event.getDemoScenarioId());
    }

    static OrderSnapshot expired(OrderExpiredEvent event) {
        return new OrderSnapshot(event.getOrderId(), event.getAccountId(), event.getSymbol(), event.getSide(),
                event.getOrderType(), event.getQuantity(), event.getCumulativeQuantity(), OrderStatus.EXPIRED,
                event.getAcceptedAt(), event.getDemoScenarioId());
    }

    static ExecutionRecord executionRecord(ExecutionReportEvent event) {
        return new ExecutionRecord(event.getEventId(), event.getExecutionId(), event.getOrderId(),
                event.getAccountId(), event.getSymbol(), event.getSide(), event.getExecutionSequence(),
                event.getExecutedQuantity(), event.getExecutionPrice(), event.getExecutedAt());
    }
}
