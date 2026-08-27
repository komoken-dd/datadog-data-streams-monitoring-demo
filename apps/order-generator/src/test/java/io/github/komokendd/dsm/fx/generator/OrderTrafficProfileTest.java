package io.github.komokendd.dsm.fx.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderTrafficProfileTest {
    private static final long SEED = 20260819L;

    @Test
    void sameSeedProducesTheSameOrderSequence() {
        var first = new OrderTrafficProfile(SEED);
        var second = new OrderTrafficProfile(SEED);
        List<OrderTrafficProfile.OrderSample> firstSequence = new ArrayList<>();
        List<OrderTrafficProfile.OrderSample> secondSequence = new ArrayList<>();

        for (int index = 0; index < 100; index++) {
            firstSequence.add(first.next());
            secondSequence.add(second.next());
        }

        assertThat(firstSequence).isEqualTo(secondSequence);
    }

    @Test
    void generatedOrdersConvergeOnTheConfiguredWeightedMix() {
        var profile = new OrderTrafficProfile(SEED);
        int sampleCount = 100_000;
        Map<String, Integer> sides = new HashMap<>();
        Map<String, Integer> buyPairs = new HashMap<>();
        Map<String, Integer> sellPairs = new HashMap<>();
        Map<BigDecimal, Integer> quantities = new HashMap<>();

        for (int index = 0; index < sampleCount; index++) {
            OrderTrafficProfile.OrderSample sample = profile.next();
            sides.merge(sample.side(), 1, Integer::sum);
            ("BUY".equals(sample.side()) ? buyPairs : sellPairs)
                    .merge(sample.currencyPair(), 1, Integer::sum);
            quantities.merge(sample.quantity(), 1, Integer::sum);
        }

        assertShare(sides, "BUY", sampleCount, 0.55);
        assertShare(sides, "SELL", sampleCount, 0.45);

        assertShare(buyPairs, "USD/JPY", sides.get("BUY"), 0.50);
        assertShare(buyPairs, "EUR/USD", sides.get("BUY"), 0.30);
        assertShare(buyPairs, "GBP/USD", sides.get("BUY"), 0.20);
        assertShare(sellPairs, "USD/JPY", sides.get("SELL"), 0.35);
        assertShare(sellPairs, "EUR/USD", sides.get("SELL"), 0.45);
        assertShare(sellPairs, "GBP/USD", sides.get("SELL"), 0.20);

        assertShare(quantities, new BigDecimal("100000.00"), sampleCount, 0.35);
        assertShare(quantities, new BigDecimal("250000.00"), sampleCount, 0.30);
        assertShare(quantities, new BigDecimal("500000.00"), sampleCount, 0.20);
        assertShare(quantities, new BigDecimal("1000000.00"), sampleCount, 0.10);
        assertShare(quantities, new BigDecimal("7500000.00"), sampleCount, 0.05);
    }

    private static <T> void assertShare(Map<T, Integer> counts, T value, int total, double expected) {
        assertThat(counts.getOrDefault(value, 0) / (double) total)
                .isCloseTo(expected, within(0.01));
    }
}
