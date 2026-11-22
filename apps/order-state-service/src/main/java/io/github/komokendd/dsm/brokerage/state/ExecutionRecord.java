package io.github.komokendd.dsm.brokerage.state;

import java.math.BigDecimal;
import java.time.Instant;

record ExecutionRecord(
        String executionEventId,
        String executionId,
        String orderId,
        String accountId,
        String symbol,
        String side,
        int sequence,
        long quantity,
        BigDecimal price,
        Instant executedAt) {
}
