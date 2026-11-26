package io.github.komokendd.dsm.brokerage.simulator;

import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Optional;

/** A repeatable trade uses existing cash as well as new proceeds, without mutating account state. */
final class SwitchTradePlan {
    static final BigDecimal MAX_NOTIONAL_JPY = new BigDecimal("500000000");
    static final long MAX_QUANTITY = 1_000_000;
    private static final long LOT = Instrument.TRADING_UNIT;

    private SwitchTradePlan() { }

    static Optional<OrderFlowGenerator.SwitchTrade> prepare(
            OrderFlowGenerator.SwitchTrade proposed, ScenarioAccountClient.Account account) {
        if (!proposed.sell().accountId().equals(account.accountId()) || account.busy()
                || account.buyingPowerJpy() == null || account.buyingPowerJpy().signum() < 0) return Optional.empty();
        var preferences = new LinkedHashSet<String>();
        preferences.add(proposed.sell().symbol());
        account.sellable().stream().filter(p -> p.sellableQuantity() >= LOT)
                .map(ScenarioAccountClient.Position::symbol).sorted().forEach(preferences::add);
        for (String symbol : preferences) {
            long available = account.sellable().stream().filter(p -> p.symbol().equals(symbol))
                    .mapToLong(ScenarioAccountClient.Position::sellableQuantity).findFirst().orElse(0);
            long quantity = Math.min(proposed.sell().quantity(), available / LOT * LOT);
            if (quantity < LOT || Instruments.find(symbol).isEmpty()) continue;
            var sell = new OrderRequest(account.accountId(), symbol, "SELL", "MARKET", quantity, null,
                    proposed.sell().demoScenarioId());
            var buys = new LinkedHashSet<>(proposed.buyPreference());
            buys.add(proposed.sell().symbol());
            buys.remove(symbol);
            return Optional.of(new OrderFlowGenerator.SwitchTrade(sell, buys.stream().toList(), proposed.budgetJpy()));
        }
        return Optional.empty();
    }

    static Optional<OrderRequest> buyAfterFill(OrderFlowGenerator.SwitchTrade trade, BigDecimal cashBeforeSale,
            BigDecimal actualProceeds, MarketView market) {
        BigDecimal budget = cashBeforeSale.add(actualProceeds.multiply(OrderFlowGenerator.SWITCH_BUDGET_RATIO));
        if (budget.signum() <= 0 || budget.compareTo(MAX_NOTIONAL_JPY) > 0) return Optional.empty();
        for (String symbol : trade.buyPreference()) {
            BigDecimal price = OrderFlowGenerator.marketablePrice(market, symbol, Side.BUY, 1);
            long lots = budget.divide(price.multiply(BigDecimal.valueOf(LOT)), 0, RoundingMode.FLOOR).longValueExact();
            long quantity = Math.min(lots * LOT, MAX_QUANTITY);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(quantity));
            // The buy must require this particular sale, even after days of residual cash.
            if (quantity >= LOT && amount.compareTo(cashBeforeSale) > 0 && amount.compareTo(budget) <= 0) {
                return Optional.of(new OrderRequest(trade.sell().accountId(), symbol, "BUY", "LIMIT", quantity,
                        price, trade.sell().demoScenarioId()));
            }
        }
        return Optional.empty();
    }
}
