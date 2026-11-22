package io.github.komokendd.dsm.brokerage.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Latest exchange quotes, polled every second; base prices when the exchange is unreachable. */
@Component
class MarketQuotes {
    static final Duration STALE_AFTER = Duration.ofSeconds(10);
    private static final Logger LOG = LoggerFactory.getLogger(MarketQuotes.class);

    private final ExchangeGatewayClient exchange;
    private final Clock clock;
    private volatile MarketView latest = MarketView.fallback();
    private volatile boolean failing;

    MarketQuotes(ExchangeGatewayClient exchange, Clock clock) {
        this.exchange = exchange;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1_000)
    void refresh() {
        try {
            latest = MarketView.of(exchange.quotes(), clock.instant());
            if (failing) {
                LOG.info("Exchange quotes available again");
                failing = false;
            }
        } catch (RuntimeException exception) {
            if (!failing) {
                LOG.info("Exchange quotes unavailable, using base prices after {} s: {}",
                        STALE_AFTER.toSeconds(), exception.getMessage());
                failing = true;
            }
        }
    }

    MarketView view() {
        MarketView current = latest;
        Instant fetchedAt = current.fetchedAt();
        if (fetchedAt == null || fetchedAt.plus(STALE_AFTER).isBefore(clock.instant())) {
            return MarketView.fallback();
        }
        return current;
    }
}
