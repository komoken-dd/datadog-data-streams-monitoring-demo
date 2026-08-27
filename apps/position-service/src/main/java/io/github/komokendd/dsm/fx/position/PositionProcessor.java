package io.github.komokendd.dsm.fx.position;

import io.github.komokendd.dsm.fx.common.EventLogContext;
import io.github.komokendd.dsm.fx.common.IdempotentEventStore;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.TradeExecutedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PositionProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(PositionProcessor.class);
    private final JdbcTemplate jdbc;
    private final IdempotentEventStore events;
    private final MeterRegistry metrics;

    PositionProcessor(JdbcTemplate jdbc, IdempotentEventStore positionEventStore, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.events = positionEventStore;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = Topics.TRADES_EXECUTED,
            groupId = "position-service-v1",
            containerFactory = "executedKafkaListenerFactory")
    @Transactional
    void onTrade(TradeExecutedEvent trade) {
        if (!events.claim(trade.getEventId())) {
            LOG.info("Duplicate trade ignored by position projection, event_id={}", trade.getEventId());
            return;
        }
        BigDecimal signedQuantity = "BUY".equals(trade.getSide())
                ? trade.getQuantity()
                : trade.getQuantity().negate();
        jdbc.update("""
                insert into position_service.net_positions (currency_pair, net_quantity, updated_at)
                values (?, ?, now())
                on conflict (currency_pair) do update
                set net_quantity = position_service.net_positions.net_quantity + excluded.net_quantity,
                    updated_at = now()
                """, trade.getCurrencyPair(), signedQuantity);
        Counter.builder("demo.fx.position.updates")
                .tag("currency_pair", trade.getCurrencyPair())
                .tag("side", trade.getSide())
                .register(metrics)
                .increment();
        try (var ignored = EventLogContext.open(
                trade.getOrderId(), trade.getEventId(), trade.getEventType(),
                trade.getCurrencyPair(), trade.getDemoScenarioId())) {
            LOG.info("Synthetic net position updated");
        }
    }
}
