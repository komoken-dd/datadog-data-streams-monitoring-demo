package io.github.komokendd.dsm.fx.risk;

import io.github.komokendd.dsm.fx.common.EventLogContext;
import io.github.komokendd.dsm.fx.common.IdempotentEventStore;
import io.github.komokendd.dsm.fx.common.Topics;
import io.github.komokendd.dsm.fx.schema.TradeExecutedEvent;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class RiskProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(RiskProcessor.class);
    private final JdbcTemplate jdbc;
    private final IdempotentEventStore events;
    private final MeterRegistry metrics;

    RiskProcessor(JdbcTemplate jdbc, IdempotentEventStore riskEventStore, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.events = riskEventStore;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = Topics.TRADES_EXECUTED,
            groupId = "risk-service-v1",
            containerFactory = "riskExecutedKafkaListenerFactory")
    @Transactional
    void onTrade(TradeExecutedEvent trade) {
        if (!events.claim(trade.getEventId())) {
            return;
        }
        BigDecimal signedExposure = "BUY".equals(trade.getSide())
                ? trade.getNotionalUsd()
                : trade.getNotionalUsd().negate();
        jdbc.update("""
                insert into risk_service.synthetic_exposure (currency_pair, exposure_usd, updated_at)
                values (?, ?, now())
                on conflict (currency_pair) do update
                set exposure_usd = risk_service.synthetic_exposure.exposure_usd + excluded.exposure_usd,
                    updated_at = now()
                """, trade.getCurrencyPair(), signedExposure);
        DistributionSummary.builder("demo.fx.risk.synthetic_exposure_change_usd")
                .description("Synthetic demo exposure change; not VaR or a regulatory measure")
                .baseUnit("USD")
                .tag("currency_pair", trade.getCurrencyPair())
                .register(metrics)
                .record(signedExposure.abs().doubleValue());
        try (var ignored = EventLogContext.open(
                trade.getOrderId(), trade.getEventId(), trade.getEventType(),
                trade.getCurrencyPair(), trade.getDemoScenarioId())) {
            LOG.info("Synthetic demo exposure updated; this is not a production risk measure");
        }
    }
}
