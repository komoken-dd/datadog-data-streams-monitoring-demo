package io.github.komokendd.dsm.brokerage.state;

enum NotificationStatus {
    PENDING,
    RETRYING,
    DEAD_LETTER,
    DELIVERED;

    /**
     * Delivery is final. A dead letter only goes back to retrying through an explicit
     * redrive, so a late retry record from an earlier attempt cannot hide a dead letter.
     */
    NotificationStatus next(NotificationStatus incoming, boolean redriven) {
        if (this == DELIVERED) {
            return DELIVERED;
        }
        if (this == DEAD_LETTER && incoming == RETRYING && !redriven) {
            return DEAD_LETTER;
        }
        if (incoming == PENDING) {
            return this;
        }
        return incoming;
    }
}
