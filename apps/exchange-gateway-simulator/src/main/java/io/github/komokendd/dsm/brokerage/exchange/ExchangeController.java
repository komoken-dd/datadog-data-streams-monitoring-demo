package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.Instruments;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ExchangeController {
    static final int MAX_PRE_OPEN_SECONDS = 600;
    static final int MAX_SELL_OFF_SECONDS = 1_800;
    private static final Logger LOG = LoggerFactory.getLogger(ExchangeController.class);

    private final MatchingEngine engine;
    private final Clock clock;
    private final AtomicReference<Instant> lastActionAt;
    private final AtomicReference<String> lastAction = new AtomicReference<>("STARTED");

    ExchangeController(MatchingEngine engine, Clock clock) {
        this.engine = engine;
        this.clock = clock;
        this.lastActionAt = new AtomicReference<>(clock.instant());
    }

    @GetMapping("/internal/market/quotes")
    List<Quote> quotes() {
        return engine.quotes();
    }

    @GetMapping("/internal/demo/status")
    ExchangeStatus status() {
        return currentStatus();
    }

    @PostMapping("/internal/demo/opening-auction")
    ExchangeStatus openingAuction(@RequestParam(defaultValue = "60") int preOpenSeconds) {
        if (preOpenSeconds < 1 || preOpenSeconds > MAX_PRE_OPEN_SECONDS) {
            throw badRequest("preOpenSeconds must be between 1 and " + MAX_PRE_OPEN_SECONDS);
        }
        Instant now = clock.instant();
        engine.startOpeningAuction(now.plus(Duration.ofSeconds(preOpenSeconds)));
        record("OPENING_AUCTION", now);
        LOG.info("Pre-open started; opening auction in {} s", preOpenSeconds);
        return currentStatus();
    }

    @PostMapping("/internal/demo/sell-off")
    ExchangeStatus sellOff(
            @RequestParam(defaultValue = "D105") String symbol,
            @RequestParam(defaultValue = "120") int durationSeconds) {
        if (Instruments.find(symbol).isEmpty()) {
            throw badRequest("Unknown symbol: " + symbol);
        }
        if (durationSeconds < 1 || durationSeconds > MAX_SELL_OFF_SECONDS) {
            throw badRequest("durationSeconds must be between 1 and " + MAX_SELL_OFF_SECONDS);
        }
        Instant now = clock.instant();
        engine.startSellOff(symbol, now.plus(Duration.ofSeconds(durationSeconds)));
        record("SELL_OFF", now);
        LOG.info("Sell-off started on {} for {} s", symbol, durationSeconds);
        return currentStatus();
    }

    @PostMapping("/internal/demo/reset")
    ExchangeStatus reset() {
        engine.reset();
        record("RESET", clock.instant());
        LOG.info("Market reset to base prices and continuous session");
        return currentStatus();
    }

    private void record(String action, Instant at) {
        lastAction.set(action);
        lastActionAt.set(at);
    }

    private ExchangeStatus currentStatus() {
        return new ExchangeStatus(
                engine.preOpenEndsAt() == null ? MarketSession.CONTINUOUS.name() : MarketSession.PRE_OPEN.name(),
                engine.preOpenEndsAt(),
                engine.sellOffs(),
                engine.quotes(),
                lastAction.get(),
                lastActionAt.get());
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record ExchangeStatus(
            String session,
            Instant preOpenEndsAt,
            Map<String, Instant> sellOffEndsAt,
            List<Quote> quotes,
            String lastAction,
            Instant lastActionAt) {
    }
}
