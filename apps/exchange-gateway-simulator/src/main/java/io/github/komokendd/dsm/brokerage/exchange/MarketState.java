package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.Instrument;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** In-memory market state of one symbol. Not thread-safe; guarded by {@link MatchingEngine}. */
public final class MarketState {
    private final Instrument instrument;
    private BigDecimal lastPrice;
    private boolean limitDown;
    private MarketSession session = MarketSession.CONTINUOUS;
    private Instant preOpenEndsAt;
    private Instant sellOffEndsAt;

    MarketState(Instrument instrument) {
        this.instrument = instrument;
        this.lastPrice = instrument.basePrice();
    }

    public Instrument instrument() {
        return instrument;
    }

    public String symbol() {
        return instrument.symbol();
    }

    public BigDecimal lastPrice() {
        return lastPrice;
    }

    public boolean limitDown() {
        return limitDown;
    }

    public MarketSession session() {
        return session;
    }

    public Instant preOpenEndsAt() {
        return preOpenEndsAt;
    }

    public Instant sellOffEndsAt() {
        return sellOffEndsAt;
    }

    public boolean sellOffActive() {
        return sellOffEndsAt != null;
    }

    void lastPrice(BigDecimal price) {
        if (!instrument.withinDailyLimit(price)) {
            throw new IllegalArgumentException("Price outside daily limit for " + symbol() + ": " + price);
        }
        this.lastPrice = price;
    }

    void markLimitDown() {
        this.lastPrice = instrument.lowerLimitPrice();
        this.limitDown = true;
    }

    void preOpenUntil(Instant endsAt) {
        this.session = MarketSession.PRE_OPEN;
        this.preOpenEndsAt = endsAt;
    }

    void openContinuous() {
        this.session = MarketSession.CONTINUOUS;
        this.preOpenEndsAt = null;
    }

    void sellOffUntil(Instant endsAt) {
        this.sellOffEndsAt = endsAt;
    }

    void endSellOff() {
        this.sellOffEndsAt = null;
    }

    void reset() {
        this.lastPrice = instrument.basePrice();
        this.limitDown = false;
        this.session = MarketSession.CONTINUOUS;
        this.preOpenEndsAt = null;
        this.sellOffEndsAt = null;
    }

    public BigDecimal changePct() {
        BigDecimal base = instrument.basePrice();
        return lastPrice.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }

    Quote toQuote() {
        return new Quote(
                symbol(),
                instrument.name(),
                instrument.basePrice(),
                lastPrice,
                instrument.upperLimitPrice(),
                instrument.lowerLimitPrice(),
                limitDown,
                changePct(),
                session.name());
    }
}
