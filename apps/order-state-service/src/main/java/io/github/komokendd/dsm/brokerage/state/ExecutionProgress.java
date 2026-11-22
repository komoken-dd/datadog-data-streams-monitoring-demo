package io.github.komokendd.dsm.brokerage.state;

import java.time.Instant;
import java.util.List;

/** Whether buying power, the trade report, and the customer notification reflect one execution. */
record ExecutionProgress(
        Instant buyingPowerReflectedAt,
        Instant tradeReportIssuedAt,
        NotificationStatus notificationStatus,
        Instant notificationDeliveredAt) {
    static final ExecutionProgress INITIAL = new ExecutionProgress(null, null, NotificationStatus.PENDING, null);

    /** First reflection wins; redelivered or late marks never clear progress. */
    ExecutionProgress apply(ExecutionMark mark) {
        return switch (mark.type()) {
            case BUYING_POWER_REFLECTED -> new ExecutionProgress(
                    first(buyingPowerReflectedAt, mark.at()), tradeReportIssuedAt,
                    notificationStatus, notificationDeliveredAt);
            case TRADE_REPORT_ISSUED -> new ExecutionProgress(
                    buyingPowerReflectedAt, first(tradeReportIssuedAt, mark.at()),
                    notificationStatus, notificationDeliveredAt);
            case NOTIFICATION_DELIVERED -> new ExecutionProgress(
                    buyingPowerReflectedAt, tradeReportIssuedAt,
                    NotificationStatus.DELIVERED, first(notificationDeliveredAt, mark.at()));
            case NOTIFICATION_RETRYING -> withNotification(NotificationStatus.RETRYING, mark.redriven());
            case NOTIFICATION_DEAD_LETTER -> withNotification(NotificationStatus.DEAD_LETTER, mark.redriven());
        };
    }

    ExecutionProgress applyAll(List<ExecutionMark> marks) {
        ExecutionProgress progress = this;
        for (ExecutionMark mark : marks) {
            progress = progress.apply(mark);
        }
        return progress;
    }

    private ExecutionProgress withNotification(NotificationStatus incoming, boolean redriven) {
        return new ExecutionProgress(buyingPowerReflectedAt, tradeReportIssuedAt,
                notificationStatus.next(incoming, redriven), notificationDeliveredAt);
    }

    private static Instant first(Instant current, Instant incoming) {
        return current != null ? current : incoming;
    }
}
