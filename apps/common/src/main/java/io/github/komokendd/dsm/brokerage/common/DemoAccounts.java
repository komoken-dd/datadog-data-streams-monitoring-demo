package io.github.komokendd.dsm.brokerage.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic fictional customer accounts used to seed buying power and holdings.
 *
 * <p>Regular accounts hold cash and shares. Switch-trader accounts start with no cash,
 * so they can buy only after a sale has been reflected in buying power.
 */
public final class DemoAccounts {
    public static final int REGULAR_ACCOUNTS = 450;
    public static final int SWITCH_TRADER_ACCOUNTS = 50;
    public static final BigDecimal REGULAR_CASH_JPY = Jpy.of(30_000_000);
    public static final BigDecimal SWITCH_TRADER_CASH_JPY = Jpy.of(0);
    static final long DEFAULT_SHARES = 3_000;
    static final long SELL_OFF_SYMBOL_SHARES = 10_000;

    private static final List<DemoAccount> ALL = build();

    private DemoAccounts() {
    }

    public static List<DemoAccount> all() {
        return ALL;
    }

    public static List<DemoAccount> regular() {
        return ALL.stream().filter(account -> !account.switchTrader()).toList();
    }

    public static List<DemoAccount> switchTraders() {
        return ALL.stream().filter(DemoAccount::switchTrader).toList();
    }

    public static String accountId(int number) {
        return "A%05d".formatted(number);
    }

    private static List<DemoAccount> build() {
        List<DemoAccount> accounts = new ArrayList<>();
        for (int number = 1; number <= REGULAR_ACCOUNTS + SWITCH_TRADER_ACCOUNTS; number++) {
            boolean switchTrader = number > REGULAR_ACCOUNTS;
            accounts.add(new DemoAccount(
                    accountId(number),
                    switchTrader,
                    switchTrader ? SWITCH_TRADER_CASH_JPY : REGULAR_CASH_JPY,
                    initialHoldings()));
        }
        return List.copyOf(accounts);
    }

    private static Map<String, Long> initialHoldings() {
        Map<String, Long> holdings = new LinkedHashMap<>();
        for (String symbol : Instruments.symbols()) {
            holdings.put(symbol, DemoScenarios.SELL_OFF_SYMBOL.equals(symbol) ? SELL_OFF_SYMBOL_SHARES : DEFAULT_SHARES);
        }
        return Map.copyOf(holdings);
    }

    public record DemoAccount(String accountId, boolean switchTrader, BigDecimal initialCashJpy, Map<String, Long> initialShares) {
    }
}
