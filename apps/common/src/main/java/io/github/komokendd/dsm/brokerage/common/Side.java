package io.github.komokendd.dsm.brokerage.common;

public enum Side {
    BUY,
    SELL;

    public static Side parse(String value) {
        return Side.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
