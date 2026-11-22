package io.github.komokendd.dsm.brokerage.common;

/** Scenario identifiers attached to generated orders so they can be filtered in logs and spans. */
public final class DemoScenarios {
    public static final String OPENING_RUSH_PREFIX = "opening-rush-";
    public static final String SELL_OFF_PREFIX = "sell-off-";
    public static final String SWITCH_TRADE = "switch-trade";

    /** Symbol hit by the news-driven sell-off scenario. */
    public static final String SELL_OFF_SYMBOL = "D105";

    private DemoScenarios() {
    }

    public static boolean isOpeningRush(String scenarioId) {
        return scenarioId != null && scenarioId.startsWith(OPENING_RUSH_PREFIX);
    }

    public static boolean isSellOff(String scenarioId) {
        return scenarioId != null && scenarioId.startsWith(SELL_OFF_PREFIX);
    }
}
