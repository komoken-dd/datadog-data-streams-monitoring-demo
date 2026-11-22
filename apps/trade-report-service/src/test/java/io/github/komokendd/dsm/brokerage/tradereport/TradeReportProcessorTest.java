package io.github.komokendd.dsm.brokerage.tradereport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.EventIds;
import io.github.komokendd.dsm.brokerage.common.JpxBusinessCalendar;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TradeReportProcessorTest {
    private final TradeReportProcessor processor = new TradeReportProcessor(
            null, null, null,
            new TradeReportCalculator(JpxBusinessCalendar.defaultCalendar(), 2, BigDecimal.ZERO),
            null);

    @Test
    void issuesReportWithIdsDerivedFromTheExecutionEvent() {
        Instant issuedAt = Instant.parse("2026-11-20T01:00:05Z");
        var report = processor.issue(execution("exec-event-1"), issuedAt);
        var again = processor.issue(execution("exec-event-1"), issuedAt.plusSeconds(30));

        assertThat(report.getEventId()).isEqualTo(EventIds.derived("exec-event-1", "TRADE_REPORT_ISSUED"));
        assertThat(report.getTradeReportId()).isEqualTo(EventIds.derived("exec-event-1", "TRADE_REPORT"));
        assertThat(again.getEventId()).isEqualTo(report.getEventId());
        assertThat(report.getEventType()).isEqualTo("TRADE_REPORT_ISSUED");
        assertThat(report.getExecutionId()).isEqualTo("X-1");
        assertThat(report.getAccountId()).isEqualTo("A00001");
        assertThat(report.getTradeDate()).isEqualTo("2026-11-20");
        assertThat(report.getSettlementDate()).isEqualTo("2026-11-25");
        assertThat(report.getExecutionAmountJpy()).isEqualByComparingTo("280000");
        assertThat(report.getSettlementAmountJpy()).isEqualByComparingTo("280000");
        assertThat(report.getCommissionJpy()).isEqualByComparingTo("0");
        assertThat(report.getIssuedAt()).isEqualTo(issuedAt);
    }

    private static ExecutionReportEvent execution(String eventId) {
        Instant executedAt = Instant.parse("2026-11-20T01:00:00Z"); // 10:00 JST Friday
        return ExecutionReportEvent.newBuilder()
                .setEventId(eventId)
                .setEventType("EXECUTION_REPORTED")
                .setEventVersion(1)
                .setOccurredAt(executedAt)
                .setAccountId("A00001")
                .setOrderId("O-1")
                .setSymbol("D101")
                .setSide("BUY")
                .setDemoScenarioId(null)
                .setOrderType("LIMIT")
                .setQuantity(100)
                .setLimitPrice(Jpy.price(new BigDecimal("2800")))
                .setReservedAmountJpy(Jpy.of(280_000))
                .setExecutionId("X-1")
                .setExecutionSequence(1)
                .setExecutedQuantity(100)
                .setExecutionPrice(Jpy.price(new BigDecimal("2800")))
                .setExecutionAmountJpy(Jpy.of(280_000))
                .setCumulativeQuantity(100)
                .setLeavesQuantity(0)
                .setOrderStatus("FILLED")
                .setAcceptedAt(executedAt.minusSeconds(1))
                .setExecutedAt(executedAt)
                .build();
    }
}
