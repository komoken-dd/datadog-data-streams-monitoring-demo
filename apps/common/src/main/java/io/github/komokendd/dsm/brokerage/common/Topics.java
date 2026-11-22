package io.github.komokendd.dsm.brokerage.common;

import java.util.List;

/** Kafka topics. Every record is keyed by {@code account_id} to keep per-account ordering. */
public final class Topics {
    public static final String ORDERS_ACCEPTED = "brokerage.orders.accepted.v1";
    public static final String EXECUTIONS = "brokerage.executions.v1";
    public static final String ORDERS_EXPIRED = "brokerage.orders.expired.v1";
    public static final String BUYING_POWER_UPDATED = "brokerage.buying-power.updated.v1";
    public static final String TRADE_REPORTS_ISSUED = "brokerage.trade-reports.issued.v1";
    public static final String NOTIFICATIONS_DELIVERED = "brokerage.notifications.delivered.v1";
    public static final String NOTIFICATIONS_RETRY_5S = "brokerage.notifications.retry.5s.v1";
    public static final String NOTIFICATIONS_RETRY_30S = "brokerage.notifications.retry.30s.v1";
    public static final String NOTIFICATIONS_DLT = "brokerage.notifications.dlt.v1";

    public static final List<String> ALL = List.of(
            ORDERS_ACCEPTED,
            EXECUTIONS,
            ORDERS_EXPIRED,
            BUYING_POWER_UPDATED,
            TRADE_REPORTS_ISSUED,
            NOTIFICATIONS_DELIVERED,
            NOTIFICATIONS_RETRY_5S,
            NOTIFICATIONS_RETRY_30S,
            NOTIFICATIONS_DLT);

    private Topics() {
    }
}
