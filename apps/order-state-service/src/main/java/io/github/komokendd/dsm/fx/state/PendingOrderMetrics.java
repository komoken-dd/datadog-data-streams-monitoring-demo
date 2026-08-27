package io.github.komokendd.dsm.fx.state;

import io.github.komokendd.dsm.fx.common.MetricNames;
import io.github.komokendd.dsm.fx.common.SyntheticFeeCalculator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class PendingOrderMetrics {
    private static final String PENDING = "('RECEIVED','APPROVED','PENDING_EXECUTION','RETRYING','DEAD_LETTER','REDRIVEN')";
    private final JdbcTemplate jdbc;
    private final AtomicLong count = new AtomicLong();
    private final AtomicReference<Double> notional = new AtomicReference<>(0.0);
    private final AtomicReference<Double> oldestActiveAge = new AtomicReference<>(0.0);
    private final Map<String, AtomicLong> countByPair = new LinkedHashMap<>();
    private final Map<String, AtomicReference<Double>> notionalByPair = new LinkedHashMap<>();
    private final Map<String, AtomicReference<Double>> expectedFeeByPair = new LinkedHashMap<>();

    PendingOrderMetrics(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        Gauge.builder(MetricNames.ORDERS_PENDING, count, AtomicLong::doubleValue).register(metrics);
        Gauge.builder(MetricNames.ORDERS_PENDING_NOTIONAL, notional, AtomicReference::get)
                .baseUnit("USD").register(metrics);
        Gauge.builder(MetricNames.OLDEST_PENDING_AGE, oldestActiveAge, AtomicReference::get)
                .baseUnit("seconds")
                .description("Age of the oldest actively processing order, excluding unresolved dead letters")
                .register(metrics);
        for (String currencyPair : SyntheticFeeCalculator.supportedCurrencyPairs()) {
            AtomicLong pairCount = new AtomicLong();
            AtomicReference<Double> pairNotional = new AtomicReference<>(0.0);
            AtomicReference<Double> pairExpectedFee = new AtomicReference<>(0.0);
            countByPair.put(currencyPair, pairCount);
            notionalByPair.put(currencyPair, pairNotional);
            expectedFeeByPair.put(currencyPair, pairExpectedFee);
            Gauge.builder(MetricNames.ORDERS_PENDING_BY_PAIR, pairCount, AtomicLong::doubleValue)
                    .tag("currency_pair", currencyPair)
                    .register(metrics);
            Gauge.builder(MetricNames.ORDERS_PENDING_NOTIONAL_BY_PAIR, pairNotional, AtomicReference::get)
                    .baseUnit("USD")
                    .tag("currency_pair", currencyPair)
                    .register(metrics);
            Gauge.builder(MetricNames.ORDERS_PENDING_EXPECTED_FEE, pairExpectedFee, AtomicReference::get)
                    .baseUnit("USD")
                    .tag("currency_pair", currencyPair)
                    .register(metrics);
        }
    }

    @Scheduled(fixedDelay = 5000)
    void refresh() {
        List<PendingAggregate> aggregates = jdbc.query("""
                select currency_pair,
                       count(*) as pending_count,
                       coalesce(sum(notional_usd), 0) as pending_notional,
                       min(submitted_at) filter (where status <> 'DEAD_LETTER') as oldest_active_submitted_at
                  from order_state_service.orders
                 where status in %s
                 group by currency_pair
                """.formatted(PENDING), (resultSet, rowNumber) -> new PendingAggregate(
                resultSet.getString("currency_pair"),
                resultSet.getLong("pending_count"),
                resultSet.getBigDecimal("pending_notional"),
                resultSet.getTimestamp("oldest_active_submitted_at") == null
                        ? null
                        : resultSet.getTimestamp("oldest_active_submitted_at").toInstant()));

        countByPair.values().forEach(value -> value.set(0));
        notionalByPair.values().forEach(value -> value.set(0.0));
        expectedFeeByPair.values().forEach(value -> value.set(0.0));

        long pendingCount = 0;
        BigDecimal pendingNotional = BigDecimal.ZERO;
        Instant oldestActive = null;
        for (PendingAggregate aggregate : aggregates) {
            AtomicLong pairCount = requirePair(countByPair, aggregate.currencyPair());
            AtomicReference<Double> pairNotional = requirePair(notionalByPair, aggregate.currencyPair());
            AtomicReference<Double> pairExpectedFee = requirePair(expectedFeeByPair, aggregate.currencyPair());
            BigDecimal expectedFee = SyntheticFeeCalculator.feeUsd(
                    aggregate.currencyPair(), aggregate.pendingNotional());

            pairCount.set(aggregate.pendingCount());
            pairNotional.set(aggregate.pendingNotional().doubleValue());
            pairExpectedFee.set(expectedFee.doubleValue());
            pendingCount += aggregate.pendingCount();
            pendingNotional = pendingNotional.add(aggregate.pendingNotional());
            if (aggregate.oldestActiveSubmittedAt() != null
                    && (oldestActive == null || aggregate.oldestActiveSubmittedAt().isBefore(oldestActive))) {
                oldestActive = aggregate.oldestActiveSubmittedAt();
            }
        }

        count.set(pendingCount);
        notional.set(pendingNotional.doubleValue());
        oldestActiveAge.set(oldestActive == null
                ? 0.0
                : (double) Math.max(0, Duration.between(oldestActive, Instant.now()).toSeconds()));
    }

    private static <T> T requirePair(Map<String, T> values, String currencyPair) {
        T value = values.get(currencyPair);
        if (value == null) {
            throw new IllegalArgumentException("Unsupported currency pair in order projection: " + currencyPair);
        }
        return value;
    }

    record PendingAggregate(
            String currencyPair,
            long pendingCount,
            BigDecimal pendingNotional,
            Instant oldestActiveSubmittedAt) {
    }
}
