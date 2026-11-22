package io.github.komokendd.dsm.brokerage.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.DemoAccounts;
import io.github.komokendd.dsm.brokerage.common.DemoScenarios;
import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.OrderType;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.common.TickSizes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OrderFlowGeneratorTest {
    private static final Set<String> REGULAR = DemoAccounts.regular().stream()
            .map(DemoAccounts.DemoAccount::accountId)
            .collect(Collectors.toSet());
    private static final Set<String> SWITCH_TRADERS = DemoAccounts.switchTraders().stream()
            .map(DemoAccounts.DemoAccount::accountId)
            .collect(Collectors.toSet());

    @Test
    void baselineOrdersAreValidForEveryMarketCondition() {
        var generator = new OrderFlowGenerator(1, 0.1);
        for (MarketView market : markets()) {
            for (int i = 0; i < 2_000; i++) {
                OrderRequest order = generator.baseline(market);
                assertValid(order, market);
                assertThat(REGULAR).contains(order.accountId());
                assertThat(order.demoScenarioId()).isNull();
            }
        }
    }

    @Test
    void baselineMixMatchesTheTrafficProfile() {
        var generator = new OrderFlowGenerator(2, 0.1);
        List<OrderRequest> orders = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            orders.add(generator.baseline(MarketView.fallback()));
        }

        double market = orders.stream().filter(o -> o.orderType().equals("MARKET")).count() / 10_000.0;
        double buys = orders.stream().filter(o -> o.side().equals("BUY")).count() / 10_000.0;
        long large = orders.stream().filter(o -> o.quantity() > 500).count();
        assertThat(market).isBetween(0.60, 0.70);
        assertThat(buys).isBetween(0.45, 0.55);
        assertThat(large).isBetween(500L, 1_100L);
        assertThat(orders.stream().map(OrderRequest::symbol).distinct()).hasSize(5);
        // Large orders are 1,000-3,000 shares unless capped by the per-order amount (D105).
        assertThat(orders).filteredOn(o -> o.quantity() > 500).allMatch(o -> o.quantity()
                >= Math.min(1_000, OrderFlowGenerator.maxQuantity(o.symbol())));
    }

    @Test
    void sameSeedReproducesTheSameFlow() {
        var first = new OrderFlowGenerator(42, 0.1);
        var second = new OrderFlowGenerator(42, 0.1);
        for (int i = 0; i < 500; i++) {
            assertThat(first.nextIsSwitchTrade()).isEqualTo(second.nextIsSwitchTrade());
            assertThat(first.baseline(MarketView.fallback())).isEqualTo(second.baseline(MarketView.fallback()));
        }
    }

    @Test
    void openingRushOrdersCarryTheScenarioId() {
        var generator = new OrderFlowGenerator(3, 0.1);
        String scenarioId = DemoScenarios.OPENING_RUSH_PREFIX + "run-1";
        for (MarketView market : markets()) {
            for (int i = 0; i < 1_000; i++) {
                OrderRequest order = generator.openingRush(market, scenarioId);
                assertValid(order, market);
                assertThat(order.demoScenarioId()).isEqualTo(scenarioId);
            }
        }
    }

    @Test
    void sellOffOrdersAreMostlyMarketSellsOnTheSellOffSymbol() {
        var generator = new OrderFlowGenerator(4, 0.1);
        String scenarioId = DemoScenarios.SELL_OFF_PREFIX + "run-1";
        List<OrderRequest> orders = new ArrayList<>();
        for (MarketView market : markets()) {
            for (int i = 0; i < 1_000; i++) {
                OrderRequest order = generator.sellOff(market, scenarioId);
                assertValid(order, market);
                orders.add(order);
            }
        }

        assertThat(orders).allSatisfy(order -> {
            assertThat(order.symbol()).isEqualTo(DemoScenarios.SELL_OFF_SYMBOL);
            assertThat(order.side()).isEqualTo("SELL");
            assertThat(order.quantity()).isBetween(100L, 1_000L);
            assertThat(order.demoScenarioId()).isEqualTo(scenarioId);
            assertThat(REGULAR).contains(order.accountId());
        });
        double market = orders.stream().filter(o -> o.orderType().equals("MARKET")).count() / (double) orders.size();
        assertThat(market).isBetween(0.80, 0.90);
    }

    @Test
    void switchTradesSellThenBuyAnotherSymbolWithinTheProceeds() {
        var generator = new OrderFlowGenerator(5, 1.0);
        for (MarketView market : markets()) {
            for (int i = 0; i < 1_000; i++) {
                OrderFlowGenerator.SwitchTrade trade = generator.switchTrade(market);
                OrderRequest sell = trade.sell();
                assertValid(sell, market);
                assertThat(SWITCH_TRADERS).contains(sell.accountId());
                assertThat(sell.side()).isEqualTo("SELL");
                assertThat(sell.orderType()).isEqualTo("MARKET");
                assertThat(sell.quantity()).isBetween(100L, 300L);
                assertThat(sell.demoScenarioId()).isEqualTo(DemoScenarios.SWITCH_TRADE);
                BigDecimal saleAmount = market.lastPrice(sell.symbol()).multiply(BigDecimal.valueOf(sell.quantity()));
                assertThat(trade.budgetJpy()).isLessThan(saleAmount);

                OrderRequest buy = generator.switchBuy(trade, market);
                assertThat(buy).isNotNull();
                assertValid(buy, market);
                assertThat(buy.accountId()).isEqualTo(sell.accountId());
                assertThat(buy.symbol()).isNotEqualTo(sell.symbol());
                assertThat(buy.side()).isEqualTo("BUY");
                assertThat(buy.orderType()).isEqualTo("LIMIT");
                assertThat(buy.demoScenarioId()).isEqualTo(DemoScenarios.SWITCH_TRADE);
                assertThat(buy.limitPrice().multiply(BigDecimal.valueOf(buy.quantity())))
                        .isLessThanOrEqualTo(trade.budgetJpy());
            }
        }
    }

    private static void assertValid(OrderRequest order, MarketView market) {
        Instrument instrument = Instruments.require(order.symbol());
        assertThat(order.quantity()).isPositive();
        assertThat(order.quantity() % Instrument.TRADING_UNIT).isZero();
        Side side = Side.parse(order.side());
        OrderType type = OrderType.parse(order.orderType());
        if (type == OrderType.MARKET) {
            assertThat(order.limitPrice()).isNull();
        } else {
            BigDecimal price = order.limitPrice();
            assertThat(TickSizes.onTick(price)).as("%s on tick", price).isTrue();
            assertThat(instrument.withinDailyLimit(price)).as("%s within limits", price).isTrue();
            BigDecimal last = TickSizes.floorToTick(market.lastPrice(order.symbol()))
                    .max(instrument.lowerLimitPrice())
                    .min(instrument.upperLimitPrice());
            if (side == Side.BUY) {
                assertThat(price).isGreaterThanOrEqualTo(last);
            } else {
                assertThat(price).isLessThanOrEqualTo(last);
            }
        }
        if (side == Side.BUY) {
            BigDecimal reservation = BuyingPowerReservation.buyReservationAmount(
                    instrument, type, order.limitPrice(), order.quantity());
            assertThat(reservation).isLessThanOrEqualTo(OrderFlowGenerator.MAX_ORDER_AMOUNT_JPY);
        }
    }

    /** Base prices, both daily limits, and off-tick quotes. */
    private static List<MarketView> markets() {
        Map<String, BigDecimal> upper = new LinkedHashMap<>();
        Map<String, BigDecimal> lower = new LinkedHashMap<>();
        Map<String, BigDecimal> offTick = new LinkedHashMap<>();
        for (Instrument instrument : Instruments.all()) {
            upper.put(instrument.symbol(), instrument.upperLimitPrice());
            lower.put(instrument.symbol(), instrument.lowerLimitPrice());
            offTick.put(instrument.symbol(), instrument.basePrice().add(new BigDecimal("3.7")));
        }
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        return List.of(
                MarketView.fallback(),
                new MarketView(upper, now),
                new MarketView(lower, now),
                new MarketView(offTick, now));
    }
}
