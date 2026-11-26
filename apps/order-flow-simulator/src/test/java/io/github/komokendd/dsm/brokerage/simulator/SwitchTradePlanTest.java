package io.github.komokendd.dsm.brokerage.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SwitchTradePlanTest {
    static OrderFlowGenerator.SwitchTrade proposed() {
        return new OrderFlowGenerator.SwitchTrade(
                new OrderRequest("A00451", "D101", "SELL", "MARKET", 300L, null, "SWITCH_TRADE"),
                List.of("D102", "D103", "D104", "D105"), new BigDecimal("800000"));
    }

    static ScenarioAccountClient.Account account(String cash, long reservedShares) {
        return new ScenarioAccountClient.Account("A00451", new BigDecimal(cash), BigDecimal.ZERO,
                List.of(new ScenarioAccountClient.Position("D101", 3000, reservedShares, 3000-reservedShares)));
    }

    @Test
    void accumulatedCashDoesNotMaskDependenceOnNewSaleProceeds() {
        var cash = new BigDecimal("50000000");
        var proceeds = new BigDecimal("840000");
        var buy = SwitchTradePlan.buyAfterFill(proposed(), cash, proceeds, MarketView.fallback()).orElseThrow();
        var cost = buy.limitPrice().multiply(BigDecimal.valueOf(buy.quantity()));
        assertThat(cost).isGreaterThan(cash).isLessThanOrEqualTo(cash.add(proceeds));
        assertThat(buy.quantity()).isLessThanOrEqualTo(1_000_000L);
    }

    @Test
    void exhaustedSymbolIsReplacedByAnActuallySellablePosition() {
        var account = new ScenarioAccountClient.Account("A00451", BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new ScenarioAccountClient.Position("D101", 0, 0, 0),
                        new ScenarioAccountClient.Position("D103", 100, 0, 100)));
        var prepared = SwitchTradePlan.prepare(proposed(), account).orElseThrow();
        assertThat(prepared.sell().symbol()).isEqualTo("D103");
        assertThat(prepared.sell().quantity()).isEqualTo(100L);
        assertThat(prepared.buyPreference()).doesNotContain("D103").contains("D101");
    }

    @Test
    void busyAndDepletedAccountsAreSkippedWithoutResettingFinancialState() {
        assertThat(SwitchTradePlan.prepare(proposed(), account("0", 100))).isEmpty();
        var empty = new ScenarioAccountClient.Account("A00451", BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        assertThat(SwitchTradePlan.prepare(proposed(), empty)).isEmpty();
    }

    @Test
    void excessiveNotionalAndBuyThatDoesNotNeedProceedsAreRejectedByPreflight() {
        assertThat(SwitchTradePlan.buyAfterFill(proposed(), new BigDecimal("600000000"),
                new BigDecimal("840000"), MarketView.fallback())).isEmpty();
        assertThat(SwitchTradePlan.buyAfterFill(proposed(), BigDecimal.ZERO,
                BigDecimal.ONE, MarketView.fallback())).isEmpty();
    }
}
