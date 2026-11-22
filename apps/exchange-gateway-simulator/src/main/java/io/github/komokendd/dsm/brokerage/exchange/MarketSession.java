package io.github.komokendd.dsm.brokerage.exchange;

public enum MarketSession {
    /** ザラ場: continuous matching. */
    CONTINUOUS,
    /** 寄り前: orders are accepted but not matched until the opening auction. */
    PRE_OPEN
}
