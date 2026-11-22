package io.github.komokendd.dsm.brokerage.simulator;

import java.math.BigDecimal;

/** Body of {@code POST /orders} on order-service. */
public record OrderRequest(
        String accountId,
        String symbol,
        String side,
        String orderType,
        long quantity,
        BigDecimal limitPrice,
        String demoScenarioId) {
}
