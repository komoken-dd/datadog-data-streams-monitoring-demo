package io.github.komokendd.dsm.brokerage.order;

import java.math.BigDecimal;

/** Body of {@code POST /orders}. Fields are validated by {@link OrderValidator}, not bean validation. */
public record CreateOrderRequest(
        String accountId,
        String symbol,
        String side,
        String orderType,
        Long quantity,
        BigDecimal limitPrice,
        String demoScenarioId) {
}
