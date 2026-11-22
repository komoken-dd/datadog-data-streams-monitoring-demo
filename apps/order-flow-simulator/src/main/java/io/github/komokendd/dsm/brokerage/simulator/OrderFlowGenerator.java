package io.github.komokendd.dsm.brokerage.simulator;

import io.github.komokendd.dsm.brokerage.common.DemoAccounts;
import io.github.komokendd.dsm.brokerage.common.DemoAccounts.DemoAccount;
import io.github.komokendd.dsm.brokerage.common.DemoScenarios;
import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.OrderType;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.common.TickSizes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Seeded, reproducible order flow. Every generated order is valid on its own (100-share lots,
 * on-tick limit prices inside the daily limits); rejects should only come from buying power.
 */
public final class OrderFlowGenerator {
    static final long LOT = Instrument.TRADING_UNIT;
    /** Keeps a market BUY's reservation (at the daily upper limit) well inside a regular account's cash. */
    static final BigDecimal MAX_ORDER_AMOUNT_JPY = new BigDecimal("15000000");
    /** Switch traders buy with slightly less than the proceeds, as the fill price can be a tick lower. */
    static final BigDecimal SWITCH_BUDGET_RATIO = new BigDecimal("0.97");

    private static final List<String> SYMBOLS = List.of("D101", "D102", "D103", "D104", "D105");
    private static final int[] WEIGHTS = {25, 20, 20, 15, 20};
    private static final double MARKET_ORDER_RATIO = 0.65;
    private static final double LARGE_ORDER_RATIO = 0.08;
    private static final double SELL_OFF_MARKET_RATIO = 0.85;

    private final Random random;
    private final double switchTradeRatio;
    private final List<DemoAccount> regular = DemoAccounts.regular();
    private final List<DemoAccount> switchTraders = DemoAccounts.switchTraders();

    public OrderFlowGenerator(long seed, double switchTradeRatio) {
        this.random = new Random(seed);
        this.switchTradeRatio = switchTradeRatio;
    }

    public synchronized boolean nextIsSwitchTrade() {
        return random.nextDouble() < switchTradeRatio;
    }

    public synchronized OrderRequest baseline(MarketView market) {
        return regularOrder(market, random.nextBoolean() ? Side.BUY : Side.SELL, null);
    }

    /** Pre-open orders before the opening auction; slightly buy-heavy like a typical morning. */
    public synchronized OrderRequest openingRush(MarketView market, String scenarioId) {
        return regularOrder(market, random.nextDouble() < 0.55 ? Side.BUY : Side.SELL, scenarioId);
    }

    /** News-driven selling on the sell-off symbol, mostly at market. */
    public synchronized OrderRequest sellOff(MarketView market, String scenarioId) {
        String symbol = DemoScenarios.SELL_OFF_SYMBOL;
        DemoAccount account = pick(regular);
        long quantity = (1 + random.nextInt(10)) * LOT;
        if (random.nextDouble() < SELL_OFF_MARKET_RATIO) {
            return marketOrder(account, symbol, Side.SELL, quantity, scenarioId);
        }
        return limitOrder(account, symbol, Side.SELL, quantity, marketablePrice(market, symbol, Side.SELL, random.nextInt(3)), scenarioId);
    }

    /** A cash-poor account sells at market now, and will buy another symbol with the proceeds. */
    public synchronized SwitchTrade switchTrade(MarketView market) {
        DemoAccount account = pick(switchTraders);
        String sellSymbol = weightedSymbol();
        List<String> buyPreference = new ArrayList<>(SYMBOLS);
        buyPreference.remove(sellSymbol);
        Collections.shuffle(buyPreference, random);
        long quantity = (1 + random.nextInt(3)) * LOT;
        // Make sure at least one other symbol is affordable with the proceeds.
        while (quantity < 3 * LOT && affordableUnits(budget(market, sellSymbol, quantity), cheapest(market, buyPreference)) == 0) {
            quantity += LOT;
        }
        OrderRequest sell = marketOrder(account, sellSymbol, Side.SELL, quantity, DemoScenarios.SWITCH_TRADE);
        return new SwitchTrade(sell, List.copyOf(buyPreference), budget(market, sellSymbol, quantity));
    }

    /**
     * The follow-up BUY: a marketable limit order on the first affordable symbol, sized to the sale
     * proceeds at current prices. Returns {@code null} when nothing is affordable. Uses no randomness.
     */
    public OrderRequest switchBuy(SwitchTrade trade, MarketView market) {
        for (String symbol : trade.buyPreference()) {
            BigDecimal price = marketablePrice(market, symbol, Side.BUY, 1);
            long units = affordableUnits(trade.budgetJpy(), price);
            if (units > 0) {
                long quantity = Math.min(units * LOT, maxQuantity(symbol));
                return new OrderRequest(
                        trade.sell().accountId(), symbol, Side.BUY.name(), OrderType.LIMIT.name(), quantity, price,
                        DemoScenarios.SWITCH_TRADE);
            }
        }
        return null;
    }

    private OrderRequest regularOrder(MarketView market, Side side, String scenarioId) {
        DemoAccount account = pick(regular);
        String symbol = weightedSymbol();
        long quantity = quantity(symbol);
        if (random.nextDouble() < MARKET_ORDER_RATIO) {
            return marketOrder(account, symbol, side, quantity, scenarioId);
        }
        BigDecimal price = marketablePrice(market, symbol, side, random.nextInt(3));
        return limitOrder(account, symbol, side, quantity, price, scenarioId);
    }

    /** 100-500 shares, occasionally 1,000-3,000 so the exchange produces partial fills. */
    private long quantity(String symbol) {
        long quantity = random.nextDouble() < LARGE_ORDER_RATIO
                ? (10 + random.nextInt(21)) * LOT
                : (1 + random.nextInt(5)) * LOT;
        return Math.min(quantity, maxQuantity(symbol));
    }

    static long maxQuantity(String symbol) {
        BigDecimal perLot = Instruments.require(symbol).upperLimitPrice().multiply(BigDecimal.valueOf(LOT));
        long lots = MAX_ORDER_AMOUNT_JPY.divide(perLot, 0, RoundingMode.FLOOR).longValue();
        return Math.max(1, lots) * LOT;
    }

    /** Last price moved {@code ticks} towards the aggressive side (BUY up, SELL down), floored and clamped. */
    static BigDecimal marketablePrice(MarketView market, String symbol, Side side, int ticks) {
        Instrument instrument = Instruments.require(symbol);
        BigDecimal last = clamp(instrument, TickSizes.floorToTick(market.lastPrice(symbol)));
        BigDecimal price = TickSizes.step(last, side == Side.BUY ? ticks : -ticks);
        return clamp(instrument, TickSizes.floorToTick(clamp(instrument, price)));
    }

    private static BigDecimal clamp(Instrument instrument, BigDecimal price) {
        return price.max(instrument.lowerLimitPrice()).min(instrument.upperLimitPrice());
    }

    private static BigDecimal budget(MarketView market, String symbol, long quantity) {
        return market.lastPrice(symbol).multiply(BigDecimal.valueOf(quantity)).multiply(SWITCH_BUDGET_RATIO);
    }

    private static BigDecimal cheapest(MarketView market, List<String> symbols) {
        return symbols.stream()
                .map(symbol -> marketablePrice(market, symbol, Side.BUY, 1))
                .min(BigDecimal::compareTo)
                .orElseThrow();
    }

    private static long affordableUnits(BigDecimal budget, BigDecimal price) {
        return budget.divide(price.multiply(BigDecimal.valueOf(LOT)), 0, RoundingMode.FLOOR).longValue();
    }

    private OrderRequest marketOrder(DemoAccount account, String symbol, Side side, long quantity, String scenarioId) {
        return new OrderRequest(account.accountId(), symbol, side.name(), OrderType.MARKET.name(), quantity, null, scenarioId);
    }

    private OrderRequest limitOrder(
            DemoAccount account, String symbol, Side side, long quantity, BigDecimal price, String scenarioId) {
        return new OrderRequest(account.accountId(), symbol, side.name(), OrderType.LIMIT.name(), quantity, price, scenarioId);
    }

    private String weightedSymbol() {
        int total = 0;
        for (int weight : WEIGHTS) {
            total += weight;
        }
        int draw = random.nextInt(total);
        for (int i = 0; i < WEIGHTS.length; i++) {
            draw -= WEIGHTS[i];
            if (draw < 0) {
                return SYMBOLS.get(i);
            }
        }
        return SYMBOLS.getLast();
    }

    private DemoAccount pick(List<DemoAccount> accounts) {
        return accounts.get(random.nextInt(accounts.size()));
    }

    /**
     * @param sell          the market SELL to submit first
     * @param buyPreference symbols to try for the follow-up BUY, in order
     * @param budgetJpy     cash the BUY may commit, from the sale at the price seen when selling
     */
    public record SwitchTrade(OrderRequest sell, List<String> buyPreference, BigDecimal budgetJpy) {
    }
}
