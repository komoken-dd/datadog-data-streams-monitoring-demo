package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.Jpy;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
class ExchangeMetrics {
    private final MeterRegistry registry;
    private final Map<String, AtomicLong> workingOrders = new LinkedHashMap<>();

    ExchangeMetrics(MeterRegistry registry, MatchingEngine engine) {
        this.registry = registry;
        for (String symbol : Instruments.symbols()) {
            Gauge.builder(MetricNames.MARKET_PRICE, engine, e -> e.quote(symbol).lastPrice().doubleValue())
                    .tag("symbol", symbol)
                    .register(registry);
            Gauge.builder(MetricNames.MARKET_PRICE_CHANGE, engine, e -> e.quote(symbol).changePct().doubleValue())
                    .tag("symbol", symbol)
                    .register(registry);
            Gauge.builder(MetricNames.MARKET_LIMIT_DOWN, engine, e -> e.quote(symbol).limitDown() ? 1 : 0)
                    .tag("symbol", symbol)
                    .register(registry);
            AtomicLong count = new AtomicLong();
            workingOrders.put(symbol, count);
            Gauge.builder(MetricNames.EXCHANGE_WORKING_ORDERS, count, AtomicLong::doubleValue)
                    .tag("symbol", symbol)
                    .register(registry);
        }
    }

    void recordFill(TickResult.Fill fill) {
        WorkingOrder order = fill.order();
        Counter.builder(MetricNames.EXECUTIONS)
                .tag("symbol", order.symbol())
                .tag("side", order.side().name())
                .tag("fill_type", fill.finalFill() ? "final" : "partial")
                .register(registry)
                .increment();
        DistributionSummary.builder(MetricNames.EXECUTION_AMOUNT)
                .tag("symbol", order.symbol())
                .tag("side", order.side().name())
                .baseUnit("JPY")
                // Emitted as a Datadog distribution so amounts can be summed across hosts.
                .publishPercentileHistogram()
                .register(registry)
                .record(Jpy.amount(fill.price(), fill.executedQuantity()).doubleValue());
        if (fill.firstExecution()) {
            DistributionSummary.builder(MetricNames.ORDER_TO_FIRST_EXECUTION)
                    .tag("symbol", order.symbol())
                    .baseUnit("milliseconds")
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Math.max(0, Duration.between(order.acceptedAt(), fill.executedAt()).toMillis()));
        }
    }

    void recordExpiry(TickResult.Expiry expiry) {
        Counter.builder(MetricNames.ORDERS_EXPIRED)
                .tag("symbol", expiry.order().symbol())
                .tag("side", expiry.order().side().name())
                .tag("reason", expiry.reason())
                .register(registry)
                .increment();
    }

    void workingOrders(Map<String, Long> countsBySymbol) {
        workingOrders.forEach((symbol, gauge) -> gauge.set(countsBySymbol.getOrDefault(symbol, 0L)));
    }
}
