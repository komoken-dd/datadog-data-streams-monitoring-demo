package io.github.komokendd.dsm.brokerage.state;

import java.time.Instant;

/** A downstream outcome for one execution, applied to its row or parked until the execution arrives. */
record ExecutionMark(Type type, Instant at, boolean redriven) {
    enum Type {
        BUYING_POWER_REFLECTED,
        TRADE_REPORT_ISSUED,
        NOTIFICATION_DELIVERED,
        NOTIFICATION_RETRYING,
        NOTIFICATION_DEAD_LETTER
    }

    /** How a parked mark is matched to the execution row once it arrives. */
    enum MatchKey {
        EXECUTION_EVENT_ID,
        EXECUTION_ID
    }

    MatchKey matchKey() {
        return type == Type.TRADE_REPORT_ISSUED ? MatchKey.EXECUTION_ID : MatchKey.EXECUTION_EVENT_ID;
    }
}
