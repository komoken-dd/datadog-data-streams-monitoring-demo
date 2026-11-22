package io.github.komokendd.dsm.brokerage.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DemoAccountsTest {
    @Test
    void producesTheSameDeterministicAccountSet() {
        assertThat(DemoAccounts.all()).hasSize(DemoAccounts.REGULAR_ACCOUNTS + DemoAccounts.SWITCH_TRADER_ACCOUNTS);
        assertThat(DemoAccounts.all().getFirst().accountId()).isEqualTo("A00001");
        assertThat(DemoAccounts.all().getLast().accountId()).isEqualTo("A00500");
        assertThat(DemoAccounts.all()).extracting(DemoAccounts.DemoAccount::accountId).doesNotHaveDuplicates();
    }

    @Test
    void switchTradersStartWithoutCashButWithShares() {
        assertThat(DemoAccounts.switchTraders()).hasSize(DemoAccounts.SWITCH_TRADER_ACCOUNTS).allSatisfy(account -> {
            assertThat(account.initialCashJpy()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.initialShares()).containsOnlyKeys(Instruments.symbols());
        });
        assertThat(DemoAccounts.regular()).allSatisfy(account ->
                assertThat(account.initialCashJpy()).isEqualByComparingTo(DemoAccounts.REGULAR_CASH_JPY));
    }

    @Test
    void sellOffSymbolHasLargerHoldingsAndAllHoldingsAreWholeTradingUnits() {
        var account = DemoAccounts.all().getFirst();
        assertThat(account.initialShares().get(DemoScenarios.SELL_OFF_SYMBOL)).isGreaterThan(account.initialShares().get("D101"));
        assertThat(account.initialShares().values()).allSatisfy(shares ->
                assertThat(shares % Instrument.TRADING_UNIT).isZero());
    }
}
