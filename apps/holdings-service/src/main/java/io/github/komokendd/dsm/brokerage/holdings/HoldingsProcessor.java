package io.github.komokendd.dsm.brokerage.holdings;

import io.github.komokendd.dsm.brokerage.common.EventLogContext;
import io.github.komokendd.dsm.brokerage.common.IdempotentEventStore;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.common.Topics;
import io.github.komokendd.dsm.brokerage.schema.ExecutionReportEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class HoldingsProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(HoldingsProcessor.class);
    private final JdbcTemplate jdbc;
    private final IdempotentEventStore events;
    private final MeterRegistry metrics;

    HoldingsProcessor(JdbcTemplate jdbc, IdempotentEventStore holdingsEventStore, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.events = holdingsEventStore;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = Topics.EXECUTIONS,
            groupId = "holdings-service-v1",
            containerFactory = "executionListenerFactory")
    @Transactional
    void onExecution(ExecutionReportEvent execution) {
        try (var log = EventLogContext.open(
                execution.getAccountId(), execution.getOrderId(), execution.getEventId(),
                execution.getEventType(), execution.getSymbol(), execution.getDemoScenarioId())) {
            if (!events.claim(execution.getEventId())) {
                LOG.info("Duplicate execution ignored by holdings");
                return;
            }
            Side side = Side.parse(execution.getSide());
            Holding current = jdbc.query("""
                            select quantity, average_cost from holdings_service.holdings
                            where account_id = ? and symbol = ? for update
                            """,
                            (rs, row) -> new Holding(rs.getLong("quantity"), rs.getBigDecimal("average_cost")),
                            execution.getAccountId(), execution.getSymbol())
                    .stream().findFirst().orElse(Holding.EMPTY);
            Holding.Update update = current.afterExecution(
                    side, execution.getExecutedQuantity(), execution.getExecutionPrice());
            Holding next = update.holding();
            jdbc.update("""
                    insert into holdings_service.holdings (account_id, symbol, quantity, average_cost, updated_at)
                    values (?, ?, ?, ?, now())
                    on conflict (account_id, symbol) do update
                    set quantity = excluded.quantity,
                        average_cost = excluded.average_cost,
                        updated_at = now()
                    """, execution.getAccountId(), execution.getSymbol(), next.quantity(), next.averageCost());
            Counter.builder(MetricNames.HOLDINGS_UPDATES)
                    .tag("symbol", execution.getSymbol())
                    .tag("side", side.name())
                    .register(metrics)
                    .increment();
            log.attribute("side", side.name())
                    .attribute("executed_quantity", execution.getExecutedQuantity())
                    .attribute("quantity_before", current.quantity())
                    .attribute("quantity_after", next.quantity())
                    .attribute("average_cost", next.averageCost());
            if (update.clampedShares() > 0) {
                LOG.warn("Sell fill exceeds held shares; holding clamped at zero, shortfall_shares={}",
                        update.clampedShares());
            } else {
                LOG.info("Holdings updated from execution");
            }
        }
    }
}
