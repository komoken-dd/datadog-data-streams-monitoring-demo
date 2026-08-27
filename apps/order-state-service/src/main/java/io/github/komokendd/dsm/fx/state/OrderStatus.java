package io.github.komokendd.dsm.fx.state;

enum OrderStatus {
    RECEIVED(1),
    APPROVED(2),
    PENDING_EXECUTION(3),
    RETRYING(4),
    DEAD_LETTER(5),
    REDRIVEN(6),
    EXECUTED(7),
    CONFIRMED(8),
    SETTLEMENT_INSTRUCTED(9),
    REJECTED(9);

    private final int rank;

    OrderStatus(int rank) {
        this.rank = rank;
    }

    boolean mayFollow(OrderStatus previous) {
        if (previous == null) {
            return this == RECEIVED;
        }
        if (previous == REJECTED || previous == SETTLEMENT_INSTRUCTED) {
            return this == previous;
        }
        return rank >= previous.rank;
    }

    int rank() {
        return rank;
    }
}

