package io.github.komokendd.dsm.brokerage.simulator;

import io.github.komokendd.dsm.brokerage.common.BuyingPowerReservation;
import io.github.komokendd.dsm.brokerage.common.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded, per-account switch trades; unknown outcomes are queried, never resubmitted. */
@Component
class SwitchTradeCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(SwitchTradeCoordinator.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration ORDER_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_POLLS = 60;
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }

    private final ScenarioAccountClient accounts;
    private final OrderSubmitter submitter;
    private final MarketQuotes quotes;
    private final SimulatorProperties properties;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> pending = new ConcurrentHashMap<>();

    @Autowired
    SwitchTradeCoordinator(ScenarioAccountClient accounts, OrderSubmitter submitter, MarketQuotes quotes,
            SimulatorProperties properties, MeterRegistry metrics, Clock clock) {
        this(accounts, submitter, quotes, properties, metrics, clock, Thread::sleep);
    }

    SwitchTradeCoordinator(ScenarioAccountClient accounts, OrderSubmitter submitter, MarketQuotes quotes,
            SimulatorProperties properties, MeterRegistry metrics, Clock clock, Sleeper sleeper) {
        this.accounts = accounts;
        this.submitter = submitter;
        this.quotes = quotes;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    void run(OrderFlowGenerator.SwitchTrade proposed) {
        String accountId = proposed.sell().accountId();
        if (!active.add(accountId)) {
            count("account_busy");
            return;
        }
        String awaiting = null;
        try {
            String previous = pending.get(accountId);
            if (previous != null) {
                if (!terminal(previous)) {
                    count("previous_outcome_pending");
                    return;
                }
                pending.remove(accountId, previous);
            }
            Optional<ScenarioAccountClient.Account> snapshot = accounts.account(accountId);
            if (snapshot.isEmpty()) { count("account_unavailable"); return; }
            var prepared = SwitchTradePlan.prepare(proposed, snapshot.get());
            if (prepared.isEmpty()) { count("preconditions_not_met"); return; }
            var trade = prepared.get();
            var sale = submitter.submit(trade.sell());
            if (definitiveRejection(sale)) { count("sell_rejected"); return; }
            awaiting = sale.orderId();
            if (awaiting == null) { count("sell_outcome_unknown"); return; }
            Awaited sold = awaitTerminal(awaiting);
            if (!sold.terminal()) { count("sell_outcome_pending"); return; }
            awaiting = null;
            if (sold.order() == null || !sold.order().fullyObservedFill()) { count("sell_not_filled"); return; }

            // This grace period starts after the actual full fill, not order acceptance.
            sleeper.sleep(Duration.ofMillis(properties.switchTradeBuyDelayMs()));
            var buy = SwitchTradePlan.buyAfterFill(trade, snapshot.get().buyingPowerJpy(),
                    sold.order().proceeds(), quotes.view());
            if (buy.isEmpty()) { count("no_buy_requiring_proceeds"); return; }
            var purchase = submitter.submit(buy.get());
            if (definitiveRejection(purchase)) {
                if (BuyingPowerReservation.INSUFFICIENT_BUYING_POWER.equals(purchase.code())) {
                    count("rejected_buying_power");
                    LOG.info("Switch-trade buy rejected after confirmed sale; proceeds not yet reflected, account_id={}", accountId);
                } else count("buy_rejected");
                return;
            }
            awaiting = purchase.orderId();
            if (awaiting == null) { count("buy_outcome_unknown"); return; }
            Awaited bought = awaitTerminal(awaiting);
            if (!bought.terminal()) { count("buy_outcome_pending"); return; }
            awaiting = null;
            count(bought.order() != null && bought.order().fullyObservedFill() ? "accepted" : "buy_not_filled");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            count("interrupted");
        } catch (RuntimeException unavailable) {
            count("inquiry_unavailable");
            LOG.info("Switch-trade preflight/inquiry unavailable, account_id={}: {}", accountId, unavailable.getMessage());
        } finally {
            if (awaiting != null) pending.put(accountId, awaiting);
            active.remove(accountId);
        }
    }

    private Awaited awaitTerminal(String orderId) throws InterruptedException {
        var deadline = clock.instant().plus(ORDER_TIMEOUT);
        for (int attempt = 0; attempt < MAX_POLLS && clock.instant().isBefore(deadline); attempt++) {
            var order = accounts.order(orderId);
            if (order.isPresent() && order.get().terminal()) return new Awaited(order.get(), true);
            if (rejectedStatus(submitter.status(orderId))) return new Awaited(null, true);
            sleeper.sleep(POLL_INTERVAL);
        }
        return new Awaited(null, false);
    }

    private boolean terminal(String orderId) {
        var order = accounts.order(orderId);
        return order.filter(ScenarioAccountClient.OrderProgress::terminal).isPresent()
                || rejectedStatus(submitter.status(orderId));
    }

    private static boolean rejectedStatus(Optional<String> status) {
        return status.filter(s -> s.equals("REJECTED") || s.equals("CANCELLED")).isPresent();
    }

    private static boolean definitiveRejection(OrderSubmitter.SubmitResult result) {
        return result.status() >= 400 && result.status() < 500;
    }

    private void count(String result) {
        Counter.builder(MetricNames.SWITCH_TRADES).tag("result", result).register(metrics).increment();
    }

    private record Awaited(ScenarioAccountClient.OrderProgress order, boolean terminal) { }
}
