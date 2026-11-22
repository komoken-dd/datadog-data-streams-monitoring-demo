package io.github.komokendd.dsm.brokerage.notification;

import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.OrderExpiredEvent;
import java.math.BigDecimal;
import java.time.Instant;

final class TestEvents {
    static final Instant EXECUTED_AT = Instant.parse("2026-11-20T00:00:01Z");

    private TestEvents() {
    }

    static ExecutionReportEvent execution(String eventId, long executedQuantity, String price) {
        return ExecutionReportEvent.newBuilder()
                .setEventId(eventId)
                .setEventType("EXECUTION_REPORTED")
                .setEventVersion(1)
                .setOccurredAt(EXECUTED_AT)
                .setAccountId("A0001")
                .setOrderId("order-1")
                .setSymbol("D105")
                .setSide("SELL")
                .setDemoScenarioId("sell-off-1")
                .setOrderType("LIMIT")
                .setQuantity(executedQuantity)
                .setLimitPrice(new BigDecimal(price).setScale(1))
                .setReservedAmountJpy(BigDecimal.ZERO)
                .setExecutionId("exec-1")
                .setExecutionSequence(1)
                .setExecutedQuantity(executedQuantity)
                .setExecutionPrice(new BigDecimal(price).setScale(1))
                .setExecutionAmountJpy(new BigDecimal(price).multiply(BigDecimal.valueOf(executedQuantity))
                        .setScale(0))
                .setCumulativeQuantity(executedQuantity)
                .setLeavesQuantity(0)
                .setOrderStatus("FILLED")
                .setAcceptedAt(EXECUTED_AT.minusSeconds(1))
                .setExecutedAt(EXECUTED_AT)
                .build();
    }

    static OrderExpiredEvent expiry(String eventId, long expiredQuantity) {
        return OrderExpiredEvent.newBuilder()
                .setEventId(eventId)
                .setEventType("ORDER_EXPIRED")
                .setEventVersion(1)
                .setOccurredAt(EXECUTED_AT)
                .setAccountId("A0002")
                .setOrderId("order-2")
                .setSymbol("D101")
                .setSide("BUY")
                .setDemoScenarioId(null)
                .setOrderType("LIMIT")
                .setQuantity(expiredQuantity + 100)
                .setLimitPrice(new BigDecimal("2500.0"))
                .setReservedAmountJpy(new BigDecimal("250000"))
                .setCumulativeQuantity(100)
                .setExpiredQuantity(expiredQuantity)
                .setReason("LIMIT_DOWN_UNFILLED")
                .setAcceptedAt(EXECUTED_AT.minusSeconds(60))
                .setExpiredAt(EXECUTED_AT)
                .build();
    }
}
