package io.github.komokendd.dsm.brokerage.common;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reference data for the fictional instruments traded in the demo. */
public final class Instruments {
    private static final List<Instrument> ALL = List.of(
            new Instrument("D101", "Demo Motors", new BigDecimal("2800")),
            new Instrument("D102", "Demo Electric", new BigDecimal("1650")),
            new Instrument("D103", "Demo Bank", new BigDecimal("980")),
            new Instrument("D104", "Demo Trading", new BigDecimal("4200")),
            new Instrument("D105", "Demo Semiconductor", new BigDecimal("12500")));
    private static final Map<String, Instrument> BY_SYMBOL = index();

    private Instruments() {
    }

    public static List<Instrument> all() {
        return ALL;
    }

    public static List<String> symbols() {
        return ALL.stream().map(Instrument::symbol).toList();
    }

    public static Optional<Instrument> find(String symbol) {
        return Optional.ofNullable(symbol == null ? null : BY_SYMBOL.get(symbol));
    }

    public static Instrument require(String symbol) {
        return find(symbol).orElseThrow(() -> new IllegalArgumentException("Unknown symbol: " + symbol));
    }

    private static Map<String, Instrument> index() {
        Map<String, Instrument> index = new LinkedHashMap<>();
        ALL.forEach(instrument -> index.put(instrument.symbol(), instrument));
        return Map.copyOf(index);
    }
}
