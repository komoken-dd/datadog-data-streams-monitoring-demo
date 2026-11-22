package io.github.komokendd.dsm.brokerage.state;

import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Current-state gauges: stuck orders, unfilled quantity, and stale buying power or notifications. */
@Component
class OrderStateMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(OrderStateMetrics.class);
    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> workingBySeries = new LinkedHashMap<>();
    private final Map<String, AtomicLong> unfilledBySeries = new LinkedHashMap<>();
    private final AtomicLong oldestWorkingAge = new AtomicLong();
    private final AtomicLong unreflected = new AtomicLong();
    private final AtomicLong oldestUnreflectedAge = new AtomicLong();
    private final AtomicLong undelivered = new AtomicLong();

    OrderStateMetrics(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        // Pre-register every symbol and side so a series drops to zero instead of disappearing.
        for (String symbol : Instruments.symbols()) {
            for (Side side : Side.values()) {
                AtomicLong working = new AtomicLong();
                AtomicLong unfilled = new AtomicLong();
                workingBySeries.put(series(symbol, side.name()), working);
                unfilledBySeries.put(series(symbol, side.name()), unfilled);
                Gauge.builder(MetricNames.ORDERS_WORKING, working, AtomicLong::doubleValue)
                        .description("Orders still working at the exchange")
                        .tag("symbol", symbol)
                        .tag("side", side.name())
                        .register(metrics);
                Gauge.builder(MetricNames.ORDERS_UNFILLED_QUANTITY, unfilled, AtomicLong::doubleValue)
                        .description("Shares not yet filled on working orders")
                        .baseUnit("shares")
                        .tag("symbol", symbol)
                        .tag("side", side.name())
                        .register(metrics);
            }
        }
        Gauge.builder(MetricNames.OLDEST_WORKING_ORDER_AGE, oldestWorkingAge, AtomicLong::doubleValue)
                .baseUnit("seconds").register(metrics);
        Gauge.builder(MetricNames.BUYING_POWER_UNREFLECTED, unreflected, AtomicLong::doubleValue)
                .description("Executions not yet reflected in the customer's buying power")
                .register(metrics);
        Gauge.builder(MetricNames.BUYING_POWER_OLDEST_UNREFLECTED_AGE, oldestUnreflectedAge, AtomicLong::doubleValue)
                .baseUnit("seconds").register(metrics);
        Gauge.builder(MetricNames.NOTIFICATIONS_UNDELIVERED, undelivered, AtomicLong::doubleValue)
                .description("Executions whose customer notification is pending, retrying, or dead-lettered")
                .register(metrics);
    }

    @Scheduled(fixedDelay = 5000)
    void refresh() {
        List<WorkingAggregate> working = jdbc.query("""
                select symbol, side, count(*) as working_count,
                       coalesce(sum(greatest(quantity - cumulative_quantity, 0)), 0) as unfilled_quantity,
                       min(accepted_at) as oldest_accepted_at
                  from order_state_service.orders
                 where status in ('ACCEPTED', 'PARTIALLY_FILLED')
                 group by symbol, side
                """, (rs, row) -> new WorkingAggregate(
                rs.getString("symbol"),
                rs.getString("side"),
                rs.getLong("working_count"),
                rs.getLong("unfilled_quantity"),
                OrderStateStore.instant(rs, "oldest_accepted_at")));
        ExecutionBacklog backlog = jdbc.queryForObject("""
                select count(*) filter (where buying_power_reflected_at is null) as unreflected,
                       min(executed_at) filter (where buying_power_reflected_at is null) as oldest_unreflected_at,
                       count(*) filter (where notification_status <> 'DELIVERED') as undelivered
                  from order_state_service.executions
                 where buying_power_reflected_at is null or notification_status <> 'DELIVERED'
                """, (rs, row) -> new ExecutionBacklog(
                rs.getLong("unreflected"),
                OrderStateStore.instant(rs, "oldest_unreflected_at"),
                rs.getLong("undelivered")));
        publish(working, backlog, Instant.now());
    }

    void publish(List<WorkingAggregate> working, ExecutionBacklog backlog, Instant now) {
        workingBySeries.values().forEach(value -> value.set(0));
        unfilledBySeries.values().forEach(value -> value.set(0));
        Instant oldestWorking = null;
        for (WorkingAggregate aggregate : working) {
            String key = series(aggregate.symbol(), aggregate.side());
            AtomicLong count = workingBySeries.get(key);
            if (count == null) {
                LOG.warn("Working orders for an unknown series are not published, series={}", key);
                continue;
            }
            count.set(aggregate.workingCount());
            unfilledBySeries.get(key).set(aggregate.unfilledQuantity());
            if (aggregate.oldestAcceptedAt() != null
                    && (oldestWorking == null || aggregate.oldestAcceptedAt().isBefore(oldestWorking))) {
                oldestWorking = aggregate.oldestAcceptedAt();
            }
        }
        oldestWorkingAge.set(ageSeconds(oldestWorking, now));
        unreflected.set(backlog.unreflected());
        oldestUnreflectedAge.set(ageSeconds(backlog.oldestUnreflectedAt(), now));
        undelivered.set(backlog.undelivered());
    }

    private static long ageSeconds(Instant since, Instant now) {
        return since == null ? 0 : Math.max(0, Duration.between(since, now).toSeconds());
    }

    private static String series(String symbol, String side) {
        return symbol + "/" + side;
    }

    record WorkingAggregate(
            String symbol, String side, long workingCount, long unfilledQuantity, Instant oldestAcceptedAt) {
    }

    record ExecutionBacklog(long unreflected, Instant oldestUnreflectedAt, long undelivered) {
    }
}
