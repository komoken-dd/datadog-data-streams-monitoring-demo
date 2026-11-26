package io.github.komokendd.dsm.brokerage.simulator;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
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
    private final SwitchTradeCoordinator switchTrades;
    private final EmissionLane regularLane = new EmissionLane();
    private final EmissionLane sellOffLane = new EmissionLane();
    private final Semaphore inFlight;
    private final ExecutorService submissions = Executors.newVirtualThreadPerTaskExecutor();

    OrderFlowScheduler(
            SimulatorControl control,
            OrderFlowGenerator generator,
            MarketQuotes quotes,
            OrderSubmitter submitter,
            SimulatorProperties properties,
            SwitchTradeCoordinator switchTrades) {
        this.control = control;
        this.generator = generator;
        this.quotes = quotes;
        this.submitter = submitter;
        this.properties = properties;
        this.switchTrades = switchTrades;
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
        if (!inFlight.tryAcquire()) return;
        try {
            submissions.execute(() -> {
                try { switchTrades.run(trade); }
                finally { inFlight.release(); }
            });
        } catch (RejectedExecutionException shuttingDown) {
            inFlight.release();
        }
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

    @PreDestroy
    void shutdown() {
        submissions.shutdown();
    }
}
