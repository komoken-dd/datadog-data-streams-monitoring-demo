package io.github.komokendd.dsm.brokerage.exchange;

import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.common.TickSizes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pure matching engine of the simulated exchange. The caller supplies the working orders
 * loaded for this tick; the engine moves prices and returns fills, expiries and book updates.
 * There is no counterparty book: the exchange is modelled as a deep market at the last price,
 * except under limit-down (ストップ安) where there are no buyers for sell orders.
 */
public final class MatchingEngine {
    private static final long LOT = Instrument.TRADING_UNIT;

    private final Random random;
    private final MatchingSettings settings;
    private final Map<String, MarketState> markets = new LinkedHashMap<>();

    public MatchingEngine(Random random, MatchingSettings settings) {
        this.random = random;
        this.settings = settings;
        Instruments.all().forEach(instrument -> markets.put(instrument.symbol(), new MarketState(instrument)));
    }

    public synchronized TickResult tick(List<WorkingOrder> orders, Instant now) {
        List<TickResult.Fill> fills = new ArrayList<>();
        List<TickResult.Expiry> expiries = new ArrayList<>();
        List<Progress> touched = new ArrayList<>();
        Map<String, List<Progress>> books = new LinkedHashMap<>();

        for (WorkingOrder order : orders) {
            if (order.status() != WorkingOrderStatus.WORKING || order.leavesQuantity() <= 0) {
                continue;
            }
            Progress progress = new Progress(order);
            if (Duration.between(order.acceptedAt(), now).compareTo(settings.maxWorkingAge()) > 0) {
                expiries.add(progress.expire(TickResult.SESSION_CLOSE, now));
                touched.add(progress);
                continue;
            }
            if (!markets.containsKey(order.symbol())) {
                continue;
            }
            books.computeIfAbsent(order.symbol(), symbol -> new ArrayList<>()).add(progress);
            touched.add(progress);
        }

        for (MarketState market : markets.values()) {
            List<Progress> book = books.getOrDefault(market.symbol(), List.of());
            boolean sellOffEnded = market.sellOffActive() && !now.isBefore(market.sellOffEndsAt());
            if (sellOffEnded) {
                market.endSellOff();
            }
            boolean preOpen = market.session() == MarketSession.PRE_OPEN && now.isBefore(market.preOpenEndsAt());
            if (!preOpen) {
                boolean openingAuction = market.session() == MarketSession.PRE_OPEN && !market.limitDown();
                if (market.session() == MarketSession.PRE_OPEN) {
                    market.openContinuous();
                }
                if (openingAuction) {
                    runOpeningAuction(market, book, now, fills);
                } else {
                    runContinuous(market, book, now, fills);
                }
            }
            if (sellOffEnded && market.limitDown()) {
                // 特別気配 never resolved: the remaining sell quantity expires unfilled.
                for (Progress progress : book) {
                    if (progress.order.side() == Side.SELL && progress.working()) {
                        expiries.add(progress.expire(TickResult.LIMIT_DOWN_UNFILLED, now));
                    }
                }
            }
        }

        List<TickResult.OrderUpdate> updates = touched.stream()
                .filter(Progress::changed)
                .map(Progress::toUpdate)
                .toList();
        return new TickResult(List.copyOf(fills), List.copyOf(expiries), updates);
    }

    /** Enters pre-open for every symbol; the opening auction runs on the first tick after {@code endsAt}. */
    public synchronized void startOpeningAuction(Instant endsAt) {
        markets.values().forEach(market -> market.preOpenUntil(endsAt));
    }

    public synchronized void startSellOff(String symbol, Instant endsAt) {
        market(symbol).sellOffUntil(endsAt);
    }

    /** Continuous session, prices back to base, sell-off and limit-down cleared. */
    public synchronized void reset() {
        markets.values().forEach(MarketState::reset);
    }

    public synchronized List<Quote> quotes() {
        return markets.values().stream().map(MarketState::toQuote).toList();
    }

    public synchronized Quote quote(String symbol) {
        return market(symbol).toQuote();
    }

    public synchronized Instant preOpenEndsAt() {
        return markets.values().stream()
                .map(MarketState::preOpenEndsAt)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    public synchronized Map<String, Instant> sellOffs() {
        Map<String, Instant> sellOffs = new LinkedHashMap<>();
        markets.values().stream()
                .filter(MarketState::sellOffActive)
                .forEach(market -> sellOffs.put(market.symbol(), market.sellOffEndsAt()));
        return sellOffs;
    }

    private MarketState market(String symbol) {
        MarketState market = markets.get(symbol);
        if (market == null) {
            throw new IllegalArgumentException("Unknown symbol: " + symbol);
        }
        return market;
    }

    /** 板寄せ: one opening price, every marketable order fills in this tick (large ones in 2-3 prints). */
    private void runOpeningAuction(MarketState market, List<Progress> book, Instant now, List<TickResult.Fill> fills) {
        BigDecimal openingPrice = openingPrice(market.instrument());
        market.lastPrice(openingPrice);
        for (Progress progress : book) {
            if (!progress.order.marketableAt(openingPrice)) {
                continue;
            }
            long units = progress.leaves() / LOT;
            if (progress.leaves() % LOT != 0 || units < 2) {
                fills.add(progress.fill(progress.leaves(), openingPrice, now, true));
                continue;
            }
            int prints = (int) Math.min(units, 2 + random.nextInt(2));
            long perPrint = units / prints;
            long remainder = units % prints;
            for (int i = 0; i < prints; i++) {
                long printUnits = perPrint + (i < remainder ? 1 : 0);
                fills.add(progress.fill(printUnits * LOT, openingPrice, now, true));
            }
        }
    }

    private void runContinuous(MarketState market, List<Progress> book, Instant now, List<TickResult.Fill> fills) {
        if (!market.limitDown()) {
            if (market.sellOffActive()) {
                applySellPressure(market, book);
            } else {
                randomWalk(market);
            }
        }
        BigDecimal price = market.lastPrice();
        for (Progress progress : book) {
            if (market.limitDown() && progress.order.side() == Side.SELL) {
                continue;
            }
            if (progress.order.marketableAt(price)) {
                fills.add(progress.fill(continuousFillQuantity(progress.leaves()), price, now, false));
            }
        }
    }

    private long continuousFillQuantity(long leaves) {
        long units = leaves / LOT;
        if (leaves % LOT != 0 || units <= 1 || random.nextDouble() < settings.fullFillProbability()) {
            return leaves;
        }
        return (1 + random.nextInt((int) Math.min(Integer.MAX_VALUE, units - 1))) * LOT;
    }

    /** Pending sell quantity drags the price down, at least one tick per matching tick, until ストップ安. */
    private void applySellPressure(MarketState market, List<Progress> book) {
        long pendingSell = book.stream()
                .filter(progress -> progress.order.side() == Side.SELL)
                .mapToLong(Progress::leaves)
                .sum();
        int ticks = (int) Math.max(1, Math.min(settings.maxSellOffTicksPerStep(), pendingSell / settings.sellPressureSharesPerTick()));
        BigDecimal lower = market.instrument().lowerLimitPrice();
        BigDecimal next = TickSizes.step(market.lastPrice(), -ticks);
        if (next.compareTo(lower) <= 0) {
            market.markLimitDown();
        } else {
            market.lastPrice(next);
        }
    }

    /** Small walk with a pull back towards the base price; never touches the daily limits. */
    private void randomWalk(MarketState market) {
        if (random.nextDouble() >= settings.randomWalkProbability()) {
            return;
        }
        BigDecimal base = market.instrument().basePrice();
        int drift = market.lastPrice().compareTo(base);
        double upProbability = drift > 0 ? 0.4 : drift < 0 ? 0.6 : 0.5;
        int direction = random.nextDouble() < upProbability ? 1 : -1;
        BigDecimal candidate = TickSizes.step(market.lastPrice(), direction);
        if (strictlyInside(market.instrument(), candidate)) {
            market.lastPrice(candidate);
        }
    }

    private BigDecimal openingPrice(Instrument instrument) {
        int range = settings.maxOpeningTicksFromBase();
        int offset = random.nextInt(2 * range + 1) - range;
        BigDecimal candidate = TickSizes.step(instrument.basePrice(), offset);
        return strictlyInside(instrument, candidate) ? candidate : instrument.basePrice();
    }

    private static boolean strictlyInside(Instrument instrument, BigDecimal price) {
        return price.compareTo(instrument.lowerLimitPrice()) > 0 && price.compareTo(instrument.upperLimitPrice()) < 0;
    }

    /** Mutable per-tick view of one order. */
    private static final class Progress {
        private final WorkingOrder order;
        private long cumulative;
        private int nextSequence;
        private WorkingOrderStatus status;
        private Instant firstExecutedAt;
        private boolean changed;

        private Progress(WorkingOrder order) {
            this.order = order;
            this.cumulative = order.cumulativeQuantity();
            this.nextSequence = order.nextExecutionSequence();
            this.status = order.status();
            this.firstExecutedAt = order.firstExecutedAt();
        }

        private long leaves() {
            return order.quantity() - cumulative;
        }

        private boolean working() {
            return status == WorkingOrderStatus.WORKING && leaves() > 0;
        }

        private boolean changed() {
            return changed;
        }

        private TickResult.Fill fill(long quantity, BigDecimal price, Instant now, boolean openingAuction) {
            if (quantity <= 0 || quantity > leaves()) {
                throw new IllegalStateException("Invalid fill quantity " + quantity + " for order " + order.orderId());
            }
            boolean first = firstExecutedAt == null;
            if (first) {
                firstExecutedAt = now;
            }
            cumulative += quantity;
            int sequence = nextSequence++;
            long leaves = leaves();
            if (leaves == 0) {
                status = WorkingOrderStatus.FILLED;
            }
            changed = true;
            return new TickResult.Fill(
                    order,
                    sequence,
                    quantity,
                    price,
                    cumulative,
                    leaves,
                    leaves == 0 ? TickResult.FILLED : TickResult.PARTIALLY_FILLED,
                    now,
                    first,
                    openingAuction);
        }

        private TickResult.Expiry expire(String reason, Instant now) {
            long expired = leaves();
            status = WorkingOrderStatus.EXPIRED;
            changed = true;
            return new TickResult.Expiry(order, cumulative, expired, reason, now);
        }

        private TickResult.OrderUpdate toUpdate() {
            return new TickResult.OrderUpdate(order.orderId(), cumulative, nextSequence, status, firstExecutedAt);
        }
    }
}
