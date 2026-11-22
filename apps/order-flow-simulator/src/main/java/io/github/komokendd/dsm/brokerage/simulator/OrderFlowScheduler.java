package io.github.komokendd.dsm.brokerage.simulator;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Decides what to send on the scheduler thread (so the seeded sequence is reproducible) and
 * submits over HTTP on virtual threads, so a slow order-service never stalls the traffic clock.
 */
@Component
class OrderFlowScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(OrderFlowScheduler.class);

    private final SimulatorControl control;
    private final OrderFlowGenerator generator;
    private final MarketQuotes quotes;
    private final OrderSubmitter submitter;
    private final SimulatorProperties properties;
    private final MeterRegistry registry;
    private final EmissionLane regularLane = new EmissionLane();
    private final EmissionLane sellOffLane = new EmissionLane();
    private final Semaphore inFlight;
    private final ExecutorService submissions = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService delayedBuys = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("switch-trade-buys").daemon().factory());

    OrderFlowScheduler(
            SimulatorControl control,
            OrderFlowGenerator generator,
            MarketQuotes quotes,
            OrderSubmitter submitter,
            SimulatorProperties properties,
            MeterRegistry registry) {
        this.control = control;
        this.generator = generator;
        this.quotes = quotes;
        this.submitter = submitter;
        this.properties = properties;
        this.registry = registry;
        this.inFlight = new Semaphore(properties.maxInFlight());
    }

    @Scheduled(fixedRate = 50)
    void tick() {
        SimulatorControl.State state = control.current();
        long now = System.nanoTime();
        int regular = regularLane.due(now, control.regularRate(state));
        int sellOff = sellOffLane.due(now, control.sellOffRate(state));
        if (regular == 0 && sellOff == 0) {
            return;
        }
        MarketView market = quotes.view();
        for (int i = 0; i < regular; i++) {
            if (state.mode() == SimulatorMode.OPENING_RUSH) {
                submitAsync(generator.openingRush(market, state.demoScenarioId()), result -> {
                });
            } else if (generator.nextIsSwitchTrade()) {
                startSwitchTrade(generator.switchTrade(market));
            } else {
                submitAsync(generator.baseline(market), result -> {
                });
            }
        }
        for (int i = 0; i < sellOff; i++) {
            submitAsync(generator.sellOff(market, state.demoScenarioId()), result -> {
            });
        }
    }

    private void startSwitchTrade(OrderFlowGenerator.SwitchTrade trade) {
        submitAsync(trade.sell(), sell -> {
            if (!sell.accepted()) {
                countSwitchTrade("sell_rejected");
                return;
            }
            try {
                delayedBuys.schedule(() -> submitSwitchBuy(trade), properties.switchTradeBuyDelayMs(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException shuttingDown) {
                LOG.debug("Switch-trade buy dropped during shutdown");
            }
        });
    }

    private void submitSwitchBuy(OrderFlowGenerator.SwitchTrade trade) {
        OrderRequest buy = generator.switchBuy(trade, quotes.view());
        if (buy == null) {
            LOG.debug("Switch trade for {} skipped: no symbol affordable with the proceeds", trade.sell().accountId());
            return;
        }
        submitAsync(buy, result -> {
            if (result.accepted()) {
                countSwitchTrade("accepted");
            } else if (result.status() == 422 && BuyingPowerReservation.INSUFFICIENT_BUYING_POWER.equals(result.code())) {
                // 売ったのに買えない: the sale has not been reflected in buying power yet.
                countSwitchTrade("rejected_buying_power");
                LOG.info("Switch-trade buy rejected for {}: sale proceeds not yet in buying power", buy.accountId());
            }
        });
    }

    private void submitAsync(OrderRequest request, Consumer<OrderSubmitter.SubmitResult> then) {
        if (!inFlight.tryAcquire()) {
            LOG.debug("Order dropped: {} submissions already in flight", properties.maxInFlight());
            return;
        }
        try {
            submissions.execute(() -> {
                try {
                    OrderSubmitter.SubmitResult result = submitter.submit(request);
                    logResult(request, result);
                    then.accept(result);
                } catch (RuntimeException exception) {
                    LOG.warn("Simulator submission callback failed: {}", exception.getMessage());
                } finally {
                    inFlight.release();
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            inFlight.release();
        }
    }

    private static void logResult(OrderRequest request, OrderSubmitter.SubmitResult result) {
        if (result.accepted()) {
            return;
        }
        if (result.status() >= 400 && result.status() < 500) {
            LOG.debug("Order rejected ({} {}): {} {} {} x{}", result.status(), result.code(),
                    request.accountId(), request.side(), request.symbol(), request.quantity());
        } else {
            LOG.info("Order submission failed ({}): {}", result.status(),
                    result.error() == null ? result.code() : result.error());
        }
    }

    private void countSwitchTrade(String result) {
        Counter.builder(MetricNames.SWITCH_TRADES).tag("result", result).register(registry).increment();
    }

    @PreDestroy
    void shutdown() {
        delayedBuys.shutdownNow();
        submissions.shutdown();
    }
}
