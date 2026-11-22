package io.github.komokendd.dsm.brokerage.simulator;

import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Snapshot of the exchange quotes used to price limit orders. */
public record MarketView(Map<String, BigDecimal> lastPrices, Instant fetchedAt) {

    /** Base prices, used before the first successful quote poll or when quotes go stale. */
    public static MarketView fallback() {
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        Instruments.all().forEach(instrument -> prices.put(instrument.symbol(), instrument.basePrice()));
        return new MarketView(Map.copyOf(prices), null);
    }

    static MarketView of(List<QuoteDto> quotes, Instant fetchedAt) {
        Map<String, BigDecimal> prices = new LinkedHashMap<>(fallback().lastPrices());
        for (QuoteDto quote : quotes) {
            if (quote.symbol() != null && quote.lastPrice() != null && prices.containsKey(quote.symbol())) {
                prices.put(quote.symbol(), quote.lastPrice());
            }
        }
        return new MarketView(Map.copyOf(prices), fetchedAt);
    }

    public BigDecimal lastPrice(String symbol) {
        Instrument instrument = Instruments.require(symbol);
        return lastPrices.getOrDefault(symbol, instrument.basePrice());
    }

    public boolean live() {
        return fetchedAt != null;
    }

    /** Wire format of {@code GET /internal/market/quotes}. */
    public record QuoteDto(
            String symbol,
            String name,
            BigDecimal basePrice,
            BigDecimal lastPrice,
            BigDecimal upperLimit,
            BigDecimal lowerLimit,
            boolean limitDown,
            BigDecimal changePct,
            String session) {
    }
}
