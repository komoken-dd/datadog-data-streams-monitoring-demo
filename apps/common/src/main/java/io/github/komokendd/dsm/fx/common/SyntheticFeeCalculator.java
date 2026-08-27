package io.github.komokendd.dsm.fx.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic, fictional fee schedule used only for the FX observability demo.
 *
 * <p>The rates are deliberately simple and must not be presented as customer pricing,
 * spread revenue, or a production fee model.</p>
 */
public final class SyntheticFeeCalculator {
    private static final BigDecimal BASIS_POINTS_PER_UNIT = new BigDecimal("10000");
    private static final List<String> SUPPORTED_CURRENCY_PAIRS = List.of("USD/JPY", "EUR/USD", "GBP/USD");
    private static final Map<String, BigDecimal> FEE_RATE_BPS = Map.of(
            "USD/JPY", new BigDecimal("0.40"),
            "EUR/USD", new BigDecimal("0.30"),
            "GBP/USD", new BigDecimal("0.50"));

    private SyntheticFeeCalculator() {
    }

    public static List<String> supportedCurrencyPairs() {
        return SUPPORTED_CURRENCY_PAIRS;
    }

    public static BigDecimal feeRateBps(String currencyPair) {
        BigDecimal rate = FEE_RATE_BPS.get(currencyPair);
        if (rate == null) {
            throw new IllegalArgumentException("Unsupported currency pair: " + currencyPair);
        }
        return rate;
    }

    public static BigDecimal feeUsd(String currencyPair, BigDecimal notionalUsd) {
        Objects.requireNonNull(notionalUsd, "notionalUsd");
        if (notionalUsd.signum() < 0) {
            throw new IllegalArgumentException("Synthetic USD notional must not be negative");
        }
        return notionalUsd
                .multiply(feeRateBps(currencyPair))
                .divide(BASIS_POINTS_PER_UNIT)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
