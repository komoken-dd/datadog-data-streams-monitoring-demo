package io.github.komokendd.dsm.brokerage.state;

import java.util.List;
import java.util.Locale;

enum OrderStatus {
    ACCEPTED(1),
    PARTIALLY_FILLED(2),
    FILLED(3),
    EXPIRED(3);

    /** Statuses that still have quantity working at the exchange. */
    static final List<OrderStatus> WORKING = List.of(ACCEPTED, PARTIALLY_FILLED);

    private final int rank;

    OrderStatus(int rank) {
        this.rank = rank;
    }

    int rank() {
        return rank;
    }

    boolean terminal() {
        return this == FILLED || this == EXPIRED;
    }

    /** Events arrive from several topics, so a lower-ranked or post-terminal status is ignored. */
    static OrderStatus resolve(OrderStatus current, OrderStatus incoming) {
        if (current == null) {
            return incoming;
        }
        if (current.terminal()) {
            return current;
        }
        return incoming.rank >= current.rank ? incoming : current;
    }

    /** Applies fill progress that a status-only comparison would miss (e.g. accepted after a fill). */
    OrderStatus withFillProgress(long cumulativeQuantity, long quantity) {
        if (terminal()) {
            return this;
        }
        if (quantity > 0 && cumulativeQuantity >= quantity) {
            return FILLED;
        }
        if (cumulativeQuantity > 0) {
            return PARTIALLY_FILLED;
        }
        return this;
    }

    static OrderStatus parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
