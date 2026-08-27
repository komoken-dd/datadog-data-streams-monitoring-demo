package io.github.komokendd.dsm.fx.deadletter;

import java.math.BigDecimal;
import java.time.Instant;

record UnresolvedDeadLetter(
        String eventId,
        String orderId,
        String currencyPair,
        String side,
        BigDecimal notionalUsd,
        String demoScenarioId,
        int retryCount,
        String failureCategory,
        Instant failureTimestamp,
        Instant receivedAt) {
}
