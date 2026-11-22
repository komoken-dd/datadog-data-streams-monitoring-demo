package io.github.komokendd.dsm.brokerage.tradereport;

import io.github.komokendd.dsm.brokerage.common.EventIds;
import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.IdempotentEventStore;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.github.komokendd.dsm.brokerage.schema.TradeReportIssuedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class TradeReportProcessor {
    static final String EVENT_TYPE = "TRADE_REPORT_ISSUED";
    private static final Logger LOG = LoggerFactory.getLogger(TradeReportProcessor.class);
    private final JdbcTemplate jdbc;
    private final IdempotentEventStore events;
    private final KafkaTemplate<String, TradeReportIssuedEvent> kafka;
    private final TradeReportCalculator calculator;
    private final MeterRegistry metrics;

    TradeReportProcessor(
            JdbcTemplate jdbc,
            IdempotentEventStore tradeReportEventStore,
            KafkaTemplate<String, TradeReportIssuedEvent> kafka,
            TradeReportCalculator calculator,
            MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.events = tradeReportEventStore;
        this.kafka = kafka;
        this.calculator = calculator;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = Topics.EXECUTIONS,
            groupId = "trade-report-service-v1",
            containerFactory = "executionListenerFactory")
    @Transactional
    void onExecution(ExecutionReportEvent execution) {
        try (var log = EventLogContext.open(
                execution.getAccountId(), execution.getOrderId(), execution.getEventId(),
                execution.getEventType(), execution.getSymbol(), execution.getDemoScenarioId())) {
            if (!events.claim(execution.getEventId())) {
                LOG.info("Duplicate execution ignored by trade reports");
                return;
            }
            TradeReportIssuedEvent report = issue(execution, Instant.now());
            jdbc.update("""
                    insert into trade_report_service.trade_reports
                        (trade_report_id, execution_event_id, execution_id, order_id, account_id, symbol, side,
                         executed_quantity, execution_price, execution_amount_jpy, commission_jpy,
                         settlement_amount_jpy, trade_date, settlement_date, executed_at, issued_at, demo_scenario_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (trade_report_id) do nothing
                    """,
                    report.getTradeReportId(), execution.getEventId(), report.getExecutionId(),
                    report.getOrderId(), report.getAccountId(), report.getSymbol(), report.getSide(),
                    report.getExecutedQuantity(), report.getExecutionPrice(), report.getExecutionAmountJpy(),
                    report.getCommissionJpy(), report.getSettlementAmountJpy(),
                    Date.valueOf(report.getTradeDate()), Date.valueOf(report.getSettlementDate()),
                    Timestamp.from(report.getExecutedAt()), Timestamp.from(report.getIssuedAt()),
                    report.getDemoScenarioId());
            kafka.send(Topics.TRADE_REPORTS_ISSUED, report.getAccountId(), report).join();
            Counter.builder(MetricNames.TRADE_REPORTS_ISSUED)
                    .tag("symbol", report.getSymbol())
                    .register(metrics)
                    .increment();
            log.attribute("trade_report_id", report.getTradeReportId())
                    .attribute("execution_id", report.getExecutionId())
                    .attribute("trade_date", report.getTradeDate())
                    .attribute("settlement_date", report.getSettlementDate())
                    .attribute("settlement_amount_jpy", report.getSettlementAmountJpy());
            LOG.info("Trade report issued, trade_date={}, settlement_date={}",
                    report.getTradeDate(), report.getSettlementDate());
        }
    }

    TradeReportIssuedEvent issue(ExecutionReportEvent execution, Instant issuedAt) {
        var terms = calculator.terms(
                Side.parse(execution.getSide()), execution.getExecutionAmountJpy(), execution.getExecutedAt());
        return TradeReportIssuedEvent.newBuilder()
                .setEventId(EventIds.derived(execution.getEventId(), EVENT_TYPE))
                .setEventType(EVENT_TYPE)
                .setEventVersion(1)
                .setOccurredAt(issuedAt)
                .setAccountId(execution.getAccountId())
                .setOrderId(execution.getOrderId())
                .setSymbol(execution.getSymbol())
                .setSide(execution.getSide())
                .setDemoScenarioId(execution.getDemoScenarioId())
                .setExecutionId(execution.getExecutionId())
                .setTradeReportId(EventIds.derived(execution.getEventId(), "TRADE_REPORT"))
                .setTradeDate(terms.tradeDate().toString())
                .setSettlementDate(terms.settlementDate().toString())
                .setExecutedQuantity(execution.getExecutedQuantity())
                .setExecutionPrice(execution.getExecutionPrice())
                .setExecutionAmountJpy(terms.executionAmountJpy())
                .setCommissionJpy(terms.commissionJpy())
                .setSettlementAmountJpy(terms.settlementAmountJpy())
                .setExecutedAt(execution.getExecutedAt())
                .setIssuedAt(issuedAt)
                .build();
    }
}
