package io.github.komokendd.dsm.brokerage.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderStateMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-20T01:10:00Z");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrderStateMetrics metrics = new OrderStateMetrics(mock(JdbcTemplate.class), registry);

    @Test
    void preRegistersEverySymbolAndSide() {
        assertThat(registry.find(MetricNames.ORDERS_WORKING).gauges())
                .hasSize(Instruments.symbols().size() * 2);
        assertThat(registry.find(MetricNames.ORDERS_UNFILLED_QUANTITY).gauges())
                .hasSize(Instruments.symbols().size() * 2);
    }

    @Test
    void publishesWorkingOrdersAndExecutionBacklog() {
        metrics.publish(List.of(
                        new OrderStateMetrics.WorkingAggregate("D105", "SELL", 40, 120_000, NOW.minusSeconds(95)),
                        new OrderStateMetrics.WorkingAggregate("D101", "BUY", 2, 300, NOW.minusSeconds(5))),
                new OrderStateMetrics.ExecutionBacklog(7, NOW.minusSeconds(12), 3),
                NOW);

        assertThat(gauge(MetricNames.ORDERS_WORKING, "D105", "SELL")).isEqualTo(40.0);
        assertThat(gauge(MetricNames.ORDERS_UNFILLED_QUANTITY, "D105", "SELL")).isEqualTo(120_000.0);
        assertThat(gauge(MetricNames.ORDERS_WORKING, "D101", "BUY")).isEqualTo(2.0);
        assertThat(gauge(MetricNames.ORDERS_WORKING, "D102", "BUY")).isZero();
        assertThat(registry.get(MetricNames.OLDEST_WORKING_ORDER_AGE).gauge().value()).isEqualTo(95.0);
        assertThat(registry.get(MetricNames.BUYING_POWER_UNREFLECTED).gauge().value()).isEqualTo(7.0);
        assertThat(registry.get(MetricNames.BUYING_POWER_OLDEST_UNREFLECTED_AGE).gauge().value()).isEqualTo(12.0);
        assertThat(registry.get(MetricNames.NOTIFICATIONS_UNDELIVERED).gauge().value()).isEqualTo(3.0);
    }

    @Test
    void seriesDropToZeroWhenOrdersComplete() {
        metrics.publish(List.of(new OrderStateMetrics.WorkingAggregate("D105", "SELL", 40, 120_000, NOW)),
                new OrderStateMetrics.ExecutionBacklog(1, NOW, 1), NOW);
        metrics.publish(List.of(), new OrderStateMetrics.ExecutionBacklog(0, null, 0), NOW);

        assertThat(gauge(MetricNames.ORDERS_WORKING, "D105", "SELL")).isZero();
        assertThat(gauge(MetricNames.ORDERS_UNFILLED_QUANTITY, "D105", "SELL")).isZero();
        assertThat(registry.get(MetricNames.OLDEST_WORKING_ORDER_AGE).gauge().value()).isZero();
        assertThat(registry.get(MetricNames.BUYING_POWER_OLDEST_UNREFLECTED_AGE).gauge().value()).isZero();
    }

    @Test
    void ignoresUnknownSeries() {
        metrics.publish(List.of(new OrderStateMetrics.WorkingAggregate("X999", "BUY", 1, 100, NOW)),
                new OrderStateMetrics.ExecutionBacklog(0, null, 0), NOW);

        assertThat(registry.find(MetricNames.ORDERS_WORKING).tag("symbol", "X999").gauge()).isNull();
    }

    private double gauge(String name, String symbol, String side) {
        return registry.get(name).tag("symbol", symbol).tag("side", side).gauge().value();
    }
}
