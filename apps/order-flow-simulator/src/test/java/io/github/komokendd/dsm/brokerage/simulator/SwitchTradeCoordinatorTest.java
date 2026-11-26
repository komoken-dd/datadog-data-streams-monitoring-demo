package io.github.komokendd.dsm.brokerage.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SwitchTradeCoordinatorTest {
    private final ScenarioAccountClient accounts = mock(ScenarioAccountClient.class);
    private final OrderSubmitter submitter = mock(OrderSubmitter.class);
    private final MarketQuotes quotes = mock(MarketQuotes.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
    private final SwitchTradeCoordinator coordinator = new SwitchTradeCoordinator(accounts, submitter, quotes,
            SimulatorProperties.defaults(), metrics, clock, clock::advance);

    SwitchTradeCoordinatorTest() {
        when(accounts.account("A00451")).thenReturn(Optional.of(SwitchTradePlanTest.account("50000000", 0)));
        when(quotes.view()).thenReturn(MarketView.fallback());
        when(submitter.status(any())).thenReturn(Optional.of("ACCEPTED"));
    }

    private static ScenarioAccountClient.OrderProgress filled(long quantity, BigDecimal price) {
        return new ScenarioAccountClient.OrderProgress("FILLED", quantity, quantity,
                List.of(new ScenarioAccountClient.Execution(quantity, price)));
    }

    @Test
    void waitsForActualSellFillAndSpendsAccumulatedCashWithoutBalanceReset() {
        var purchased = new AtomicReference<OrderRequest>();
        when(submitter.submit(any())).thenAnswer(invocation -> {
            OrderRequest request = invocation.getArgument(0);
            if (request.side().equals("SELL")) return new OrderSubmitter.SubmitResult(202, null, null, "sell");
            purchased.set(request);
            return new OrderSubmitter.SubmitResult(202, null, null, "buy");
        });
        when(accounts.order("sell")).thenReturn(Optional.of(new ScenarioAccountClient.OrderProgress(
                "PARTIALLY_FILLED", 300, 100, List.of(new ScenarioAccountClient.Execution(100, new BigDecimal("2800"))))),
                Optional.of(filled(300, new BigDecimal("2800"))));
        when(accounts.order("buy")).thenAnswer(i -> Optional.of(filled(purchased.get().quantity(), purchased.get().limitPrice())));
        coordinator.run(SwitchTradePlanTest.proposed());
        var order = inOrder(submitter, accounts);
        order.verify(submitter).submit(any());
        order.verify(accounts).order("sell");
        order.verify(submitter).status("sell");
        order.verify(accounts).order("sell");
        order.verify(submitter).submit(any());
        assertThat(purchased.get().limitPrice().multiply(BigDecimal.valueOf(purchased.get().quantity())))
                .isGreaterThan(new BigDecimal("50000000"));
        assertThat(metrics.get("demo.brokerage.simulator.switch_trades").tag("result", "accepted").counter().count()).isEqualTo(1);
    }

    @Test
    void unknownSellIsQueriedAndNeverAutomaticallyResubmitted() {
        when(submitter.submit(any())).thenReturn(new OrderSubmitter.SubmitResult(503,
                "RESERVATION_OUTCOME_PENDING", null, "sell"));
        when(accounts.order("sell")).thenReturn(Optional.empty());
        when(submitter.status("sell")).thenReturn(Optional.of("UNKNOWN"));
        coordinator.run(SwitchTradePlanTest.proposed());
        coordinator.run(SwitchTradePlanTest.proposed());
        verify(submitter, times(1)).submit(any());
        assertThat(metrics.get("demo.brokerage.simulator.switch_trades")
                .tag("result", "previous_outcome_pending").counter().count()).isEqualTo(1);
    }

    @Test
    void expiredSellNeverCreatesFollowUpBuy() {
        when(submitter.submit(any())).thenReturn(new OrderSubmitter.SubmitResult(202, null, null, "sell"));
        when(accounts.order("sell")).thenReturn(Optional.of(new ScenarioAccountClient.OrderProgress(
                "EXPIRED", 300, 100, List.of(new ScenarioAccountClient.Execution(100, new BigDecimal("2800"))))));
        coordinator.run(SwitchTradePlanTest.proposed());
        verify(submitter, times(1)).submit(any());
        assertThat(metrics.get("demo.brokerage.simulator.switch_trades")
                .tag("result", "sell_not_filled").counter().count()).isEqualTo(1);
    }
}
