package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.EventIds;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.OrderType;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderAcceptedEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.math.BigDecimal;

/** Avro mapping. Ids derive from the accepted-order event id so a retried tick republishes the same ids. */
final class ExchangeEvents {
    static final String EXECUTION_REPORTED = "EXECUTION_REPORTED";
    static final String ORDER_EXPIRED = "ORDER_EXPIRED";

    private ExchangeEvents() {
    }

    static WorkingOrder workingOrder(OrderAcceptedEvent event) {
        return new WorkingOrder(
                event.getOrderId(),
                event.getEventId(),
                event.getAccountId(),
                event.getSymbol(),
                Side.parse(event.getSide()),
                OrderType.parse(event.getOrderType()),
                event.getQuantity(),
                event.getLimitPrice(),
                event.getReservedAmountJpy(),
                event.getDemoScenarioId(),
                0,
                1,
                WorkingOrderStatus.WORKING,
                event.getAcceptedAt(),
                null);
    }

    static String executionEventId(WorkingOrder order, int sequence) {
        return EventIds.derived(order.eventId(), "EXECUTION-" + sequence);
    }

    static String executionId(WorkingOrder order, int sequence) {
        return EventIds.derived(order.eventId(), "EXECUTION_ID-" + sequence);
    }

    static String expiryEventId(WorkingOrder order) {
        return EventIds.derived(order.eventId(), ORDER_EXPIRED);
    }

    static ExecutionReportEvent executionReport(TickResult.Fill fill) {
        WorkingOrder order = fill.order();
        return ExecutionReportEvent.newBuilder()
                .setEventId(executionEventId(order, fill.sequence()))
                .setEventType(EXECUTION_REPORTED)
                .setEventVersion(1)
                .setOccurredAt(fill.executedAt())
                .setAccountId(order.accountId())
                .setOrderId(order.orderId())
                .setSymbol(order.symbol())
                .setSide(order.side().name())
                .setDemoScenarioId(order.demoScenarioId())
                .setOrderType(order.orderType().name())
                .setQuantity(order.quantity())
                .setLimitPrice(limitPrice(order))
                .setReservedAmountJpy(order.reservedAmountJpy())
                .setExecutionId(executionId(order, fill.sequence()))
                .setExecutionSequence(fill.sequence())
                .setExecutedQuantity(fill.executedQuantity())
                .setExecutionPrice(Jpy.price(fill.price()))
                .setExecutionAmountJpy(Jpy.amount(fill.price(), fill.executedQuantity()))
                .setCumulativeQuantity(fill.cumulativeQuantity())
                .setLeavesQuantity(fill.leavesQuantity())
                .setOrderStatus(fill.orderStatus())
                .setAcceptedAt(order.acceptedAt())
                .setExecutedAt(fill.executedAt())
                .build();
    }

    static OrderExpiredEvent orderExpired(TickResult.Expiry expiry) {
        WorkingOrder order = expiry.order();
        return OrderExpiredEvent.newBuilder()
                .setEventId(expiryEventId(order))
                .setEventType(ORDER_EXPIRED)
                .setEventVersion(1)
                .setOccurredAt(expiry.expiredAt())
                .setAccountId(order.accountId())
                .setOrderId(order.orderId())
                .setSymbol(order.symbol())
                .setSide(order.side().name())
                .setDemoScenarioId(order.demoScenarioId())
                .setOrderType(order.orderType().name())
                .setQuantity(order.quantity())
                .setLimitPrice(limitPrice(order))
                .setReservedAmountJpy(order.reservedAmountJpy())
                .setCumulativeQuantity(expiry.cumulativeQuantity())
                .setExpiredQuantity(expiry.expiredQuantity())
                .setReason(expiry.reason())
                .setAcceptedAt(order.acceptedAt())
                .setExpiredAt(expiry.expiredAt())
                .build();
    }

    private static BigDecimal limitPrice(WorkingOrder order) {
        return order.limitPrice() == null ? null : Jpy.price(order.limitPrice());
    }
}
