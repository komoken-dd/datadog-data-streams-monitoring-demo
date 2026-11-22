package io.github.komokendd.dsm.brokerage.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.komokendd.dsm.brokerage.common.Instrument;
import io.github.komokendd.dsm.brokerage.common.Instruments;
import io.github.komokendd.dsm.brokerage.common.Side;
import io.github.komokendd.dsm.brokerage.common.TickSizes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {
    private static final Instant T0 = Instant.parse("2026-11-24T00:00:00Z");
    private static final Duration TICK = Duration.ofMillis(250);

    @Test
    void partialFillsAddUpToTheOrderQuantity() {
        for (long seed = 1; seed <= 25; seed++) {
            var settings = new MatchingSettings(0.3, 0.15, 1_500, 10, 3, Duration.ofMinutes(10));
            var engine = new MatchingEngine(new Random(seed), settings);
            var book = new Orders.Book();
            book.add(Orders.market("big", "D101", Side.BUY, 3_000, T0));

            Instant now = T0;
            for (int i = 0; i < 200 && book.size() > 0; i++) {
                now = now.plus(TICK);
                book.tick(engine, now);
            }

            List<TickResult.Fill> fills = book.fills;
            assertThat(book.size()).isZero();
            assertThat(fills.stream().mapToLong(TickResult.Fill::executedQuantity).sum()).isEqualTo(3_000);
            long cumulative = 0;
            for (int i = 0; i < fills.size(); i++) {
                TickResult.Fill fill = fills.get(i);
                cumulative += fill.executedQuantity();
                assertThat(fill.sequence()).isEqualTo(i + 1);
                assertThat(fill.executedQuantity() % Instrument.TRADING_UNIT).isZero();
                assertThat(fill.cumulativeQuantity()).isEqualTo(cumulative);
                assertThat(fill.leavesQuantity()).isEqualTo(3_000 - cumulative);
                assertThat(fill.firstExecution()).isEqualTo(i == 0);
                boolean last = i == fills.size() - 1;
                assertThat(fill.orderStatus()).isEqualTo(last ? TickResult.FILLED : TickResult.PARTIALLY_FILLED);
                assertOnTickWithinLimits("D101", fill.price());
            }
            assertThat(fills.getLast().leavesQuantity()).isZero();
        }
    }

    @Test
    void lowFullFillProbabilityProducesSeveralFills() {
        var settings = new MatchingSettings(0.01, 0, 1_500, 10, 3, Duration.ofMinutes(10));
        var engine = new MatchingEngine(new Random(42), settings);
        var book = new Orders.Book();
        book.add(Orders.market("big", "D104", Side.SELL, 2_000, T0));

        Instant now = T0;
        while (book.size() > 0) {
            now = now.plus(TICK);
            book.tick(engine, now);
        }

        assertThat(book.fills).hasSizeGreaterThan(1);
        assertThat(book.fills.getLast().orderStatus()).isEqualTo(TickResult.FILLED);
    }

    @Test
    void storedProgressContinuesTheExecutionSequence() {
        var engine = new MatchingEngine(new Random(1), new MatchingSettings(1.0, 0, 1_500, 10, 3, Duration.ofMinutes(10)));
        WorkingOrder base = Orders.market("o1", "D103", Side.BUY, 500, T0);
        WorkingOrder resumed = base.apply(new TickResult.OrderUpdate("o1", 300, 3, WorkingOrderStatus.WORKING, T0));

        TickResult result = engine.tick(List.of(resumed), T0.plusSeconds(1));

        assertThat(result.fills()).singleElement().satisfies(fill -> {
            assertThat(fill.sequence()).isEqualTo(3);
            assertThat(fill.executedQuantity()).isEqualTo(200);
            assertThat(fill.cumulativeQuantity()).isEqualTo(500);
            assertThat(fill.firstExecution()).isFalse();
        });
        assertThat(result.updates()).singleElement().satisfies(update -> {
            assertThat(update.nextExecutionSequence()).isEqualTo(4);
            assertThat(update.status()).isEqualTo(WorkingOrderStatus.FILLED);
            assertThat(update.firstExecutedAt()).isEqualTo(T0);
        });
    }

    @Test
    void nonMarketableLimitOrdersStayWorking() {
        var engine = new MatchingEngine(new Random(3), new MatchingSettings(1.0, 0, 1_500, 10, 3, Duration.ofMinutes(10)));
        Instrument d102 = Instruments.require("D102");
        var book = new Orders.Book();
        book.add(Orders.limit("buy-low", "D102", Side.BUY, 100, d102.lowerLimitPrice(), T0));
        book.add(Orders.limit("sell-high", "D102", Side.SELL, 100, d102.upperLimitPrice(), T0));
        book.add(Orders.limit("buy-at-last", "D102", Side.BUY, 100, d102.basePrice(), T0));

        for (int i = 1; i <= 20; i++) {
            book.tick(engine, T0.plus(TICK.multipliedBy(i)));
        }

        assertThat(book.fills).extracting(fill -> fill.order().orderId()).containsExactly("buy-at-last");
        assertThat(book.orders()).extracting(WorkingOrder::orderId).containsExactly("buy-low", "sell-high");
    }

    @Test
    void openingAuctionFillsEverythingAtOnePriceOnlyAfterPreOpen() {
        var engine = new MatchingEngine(new Random(11), MatchingSettings.DEFAULTS);
        engine.startOpeningAuction(T0.plusSeconds(60));
        var book = new Orders.Book();
        for (int i = 0; i < 40; i++) {
            Side side = i % 2 == 0 ? Side.BUY : Side.SELL;
            long quantity = i % 5 == 0 ? 1_500 : 100 * (1 + i % 4);
            book.add(Orders.market("o" + i, "D102", side, quantity, T0.plusSeconds(i)));
        }
        Instrument d102 = Instruments.require("D102");
        book.add(Orders.limit("unmarketable", "D102", Side.BUY, 100, d102.lowerLimitPrice(), T0));

        for (int second = 1; second < 60; second++) {
            TickResult result = book.tick(engine, T0.plusSeconds(second));
            assertThat(result.fills()).isEmpty();
        }
        assertThat(engine.quote("D102").session()).isEqualTo(MarketSession.PRE_OPEN.name());
        assertThat(engine.quote("D102").lastPrice()).isEqualByComparingTo(d102.basePrice());

        TickResult auction = book.tick(engine, T0.plusSeconds(60));

        assertThat(auction.fills()).isNotEmpty().allMatch(TickResult.Fill::openingAuction);
        Map<String, List<TickResult.Fill>> byOrder = auction.fills().stream()
                .collect(Collectors.groupingBy(fill -> fill.order().orderId()));
        assertThat(byOrder).hasSize(40).doesNotContainKey("unmarketable");
        assertThat(auction.fills().stream().map(TickResult.Fill::price).distinct()).hasSize(1);
        BigDecimal openingPrice = auction.fills().getFirst().price();
        assertOnTickWithinLimits("D102", openingPrice);
        assertThat(openingPrice.subtract(d102.basePrice()).abs()).isLessThanOrEqualTo(new BigDecimal("3"));
        byOrder.forEach((orderId, fills) -> {
            WorkingOrder order = fills.getFirst().order();
            assertThat(fills.stream().mapToLong(TickResult.Fill::executedQuantity).sum()).isEqualTo(order.quantity());
            assertThat(fills.getLast().orderStatus()).isEqualTo(TickResult.FILLED);
            if (order.quantity() >= 200) {
                assertThat(fills).hasSizeBetween(2, 3);
            }
        });
        assertThat(engine.quote("D102").session()).isEqualTo(MarketSession.CONTINUOUS.name());
        assertThat(book.orders()).extracting(WorkingOrder::orderId).containsExactly("unmarketable");
    }

    @Test
    void sellOffReachesLimitDownStopsSellsAndExpiresThemAtTheEnd() {
        var engine = new MatchingEngine(new Random(5), MatchingSettings.DEFAULTS);
        Instrument d105 = Instruments.require("D105");
        engine.startSellOff("D105", T0.plusSeconds(120));
        var book = new Orders.Book();

        Instant now = T0;
        BigDecimal previous = d105.basePrice();
        int sequence = 0;
        while (!engine.quote("D105").limitDown()) {
            now = now.plus(TICK);
            for (int i = 0; i < 6; i++) {
                book.add(Orders.market("s" + sequence++, "D105", Side.SELL, 500, now));
            }
            book.tick(engine, now);
            BigDecimal price = engine.quote("D105").lastPrice();
            assertThat(price).isLessThan(previous);
            assertOnTickWithinLimits("D105", price);
            previous = price;
        }
        assertThat(now).isBefore(T0.plusSeconds(120));
        assertThat(engine.quote("D105").lastPrice()).isEqualByComparingTo(d105.lowerLimitPrice());
        assertThat(book.fills).allSatisfy(fill -> assertOnTickWithinLimits("D105", fill.price()));

        int fillsBeforeLimitDown = book.fills.size();
        for (int i = 0; i < 20; i++) {
            now = now.plus(TICK);
            book.add(Orders.market("late-sell-" + i, "D105", Side.SELL, 300, now));
            book.add(Orders.market("buy-" + i, "D105", Side.BUY, 100, now));
            book.tick(engine, now);
        }
        List<TickResult.Fill> limitDownFills = book.fills.subList(fillsBeforeLimitDown, book.fills.size());
        assertThat(limitDownFills).isNotEmpty().allSatisfy(fill -> {
            assertThat(fill.order().side()).isEqualTo(Side.BUY);
            assertThat(fill.price()).isEqualByComparingTo(d105.lowerLimitPrice());
        });

        List<WorkingOrder> remainingSells = book.orders().stream()
                .filter(order -> order.side() == Side.SELL)
                .toList();
        assertThat(remainingSells).isNotEmpty();
        TickResult end = book.tick(engine, T0.plusSeconds(120));

        assertThat(end.fills()).allSatisfy(fill -> assertThat(fill.order().side()).isEqualTo(Side.BUY));
        assertThat(end.expiries()).hasSize(remainingSells.size()).allSatisfy(expiry -> {
            assertThat(expiry.reason()).isEqualTo(TickResult.LIMIT_DOWN_UNFILLED);
            assertThat(expiry.order().side()).isEqualTo(Side.SELL);
            assertThat(expiry.expiredQuantity())
                    .isPositive()
                    .isEqualTo(expiry.order().quantity() - expiry.cumulativeQuantity());
        });
        assertThat(book.orders()).noneMatch(order -> order.side() == Side.SELL);
        assertThat(engine.sellOffs()).isEmpty();
        assertThat(engine.quote("D105").limitDown()).isTrue();
        assertThat(engine.quote("D105").lastPrice()).isEqualByComparingTo(d105.lowerLimitPrice());

        engine.reset();
        assertThat(engine.quote("D105").limitDown()).isFalse();
        assertThat(engine.quote("D105").lastPrice()).isEqualByComparingTo(d105.basePrice());
    }

    @Test
    void sellOffOnlyMovesTheTargetSymbol() {
        var engine = new MatchingEngine(new Random(9), new MatchingSettings(0.6, 0, 1_500, 10, 3, Duration.ofMinutes(10)));
        engine.startSellOff("D105", T0.plusSeconds(30));

        for (int i = 1; i <= 40; i++) {
            engine.tick(List.of(), T0.plus(TICK.multipliedBy(i)));
        }

        assertThat(engine.quote("D105").lastPrice()).isLessThan(Instruments.require("D105").basePrice());
        assertThat(engine.quote("D101").lastPrice()).isEqualByComparingTo(Instruments.require("D101").basePrice());
    }

    @Test
    void staleWorkingOrdersExpireWithSessionClose() {
        var engine = new MatchingEngine(new Random(2), MatchingSettings.DEFAULTS);
        Instrument d103 = Instruments.require("D103");
        WorkingOrder stale = Orders.limit("stale", "D103", Side.BUY, 500, d103.lowerLimitPrice(), T0)
                .apply(new TickResult.OrderUpdate("stale", 200, 2, WorkingOrderStatus.WORKING, T0));

        TickResult result = engine.tick(List.of(stale), T0.plus(Duration.ofMinutes(11)));

        assertThat(result.fills()).isEmpty();
        assertThat(result.expiries()).singleElement().satisfies(expiry -> {
            assertThat(expiry.reason()).isEqualTo(TickResult.SESSION_CLOSE);
            assertThat(expiry.expiredQuantity()).isEqualTo(300);
            assertThat(expiry.cumulativeQuantity()).isEqualTo(200);
        });
        assertThat(result.updates()).singleElement()
                .satisfies(update -> assertThat(update.status()).isEqualTo(WorkingOrderStatus.EXPIRED));
    }

    @Test
    void randomWalkPricesStayOnTickAndInsideDailyLimits() {
        var engine = new MatchingEngine(new Random(77), new MatchingSettings(0.6, 1.0, 1_500, 10, 3, Duration.ofMinutes(10)));
        for (int i = 1; i <= 20_000; i++) {
            engine.tick(List.of(), T0.plus(TICK.multipliedBy(i)));
            if (i % 50 == 0) {
                engine.quotes().forEach(quote -> {
                    assertOnTickWithinLimits(quote.symbol(), quote.lastPrice());
                    assertThat(quote.limitDown()).isFalse();
                });
            }
        }
    }

    @Test
    void resetCancelsPreOpenAndSellOff() {
        var engine = new MatchingEngine(new Random(4), MatchingSettings.DEFAULTS);
        engine.startOpeningAuction(T0.plusSeconds(60));
        engine.startSellOff("D105", T0.plusSeconds(120));

        engine.reset();

        assertThat(engine.preOpenEndsAt()).isNull();
        assertThat(engine.sellOffs()).isEmpty();
        assertThat(engine.quotes()).allSatisfy(quote -> {
            assertThat(quote.session()).isEqualTo(MarketSession.CONTINUOUS.name());
            assertThat(quote.lastPrice()).isEqualByComparingTo(quote.basePrice());
            assertThat(quote.changePct()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    private static void assertOnTickWithinLimits(String symbol, BigDecimal price) {
        Instrument instrument = Instruments.require(symbol);
        assertThat(TickSizes.onTick(price)).as("%s %s on tick", symbol, price).isTrue();
        assertThat(instrument.withinDailyLimit(price)).as("%s %s within limits", symbol, price).isTrue();
    }
}
