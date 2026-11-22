package io.github.komokendd.dsm.brokerage.common;

public enum OrderType {
    /** 成行. Buying power is reserved at the daily upper limit price. */
    MARKET,
    /** 指値. Buying power is reserved at the limit price. */
    LIMIT;

    public static OrderType parse(String value) {
        return OrderType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
