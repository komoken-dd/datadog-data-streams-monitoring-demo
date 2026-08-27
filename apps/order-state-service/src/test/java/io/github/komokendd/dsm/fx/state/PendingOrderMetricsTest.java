package io.github.komokendd.dsm.fx.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.github.komokendd.dsm.fx.common.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PendingOrderMetricsTest {
    @Test
    void publishesCurrentPendingVolumeAndExpectedFeesByCurrencyPair() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),
                ArgumentMatchers.<RowMapper<PendingOrderMetrics.PendingAggregate>>any())).thenReturn(List.of(
                new PendingOrderMetrics.PendingAggregate(
                        "EUR/USD", 2, new BigDecimal("1000000.00"), Instant.now().minusSeconds(20)),
                new PendingOrderMetrics.PendingAggregate(
                        "GBP/USD", 1, new BigDecimal("500000.00"), Instant.now().minusSeconds(10)),
                new PendingOrderMetrics.PendingAggregate(
                        "USD/JPY", 1, new BigDecimal("100000.00"), null)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PendingOrderMetrics metrics = new PendingOrderMetrics(jdbc, registry);

        metrics.refresh();

        assertThat(registry.get(MetricNames.ORDERS_PENDING).gauge().value()).isEqualTo(4.0);
        assertThat(registry.get(MetricNames.ORDERS_PENDING_NOTIONAL).gauge().value()).isEqualTo(1600000.0);
        assertThat(registry.get(MetricNames.ORDERS_PENDING_BY_PAIR)
                .tag("currency_pair", "EUR/USD").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get(MetricNames.ORDERS_PENDING_NOTIONAL_BY_PAIR)
                .tag("currency_pair", "GBP/USD").gauge().value()).isEqualTo(500000.0);
        assertThat(registry.get(MetricNames.ORDERS_PENDING_EXPECTED_FEE)
                .tag("currency_pair", "EUR/USD").gauge().value()).isEqualTo(30.0);
        assertThat(registry.get(MetricNames.ORDERS_PENDING_EXPECTED_FEE)
                .tag("currency_pair", "GBP/USD").gauge().value()).isEqualTo(25.0);
        assertThat(registry.get(MetricNames.ORDERS_PENDING_EXPECTED_FEE)
                .tag("currency_pair", "USD/JPY").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get(MetricNames.OLDEST_PENDING_AGE).gauge().value())
                .isBetween(19.0, 21.0);
    }
}
