package io.github.komokendd.dsm.brokerage.common;

/**
 * Custom metric names. Tags are bounded business dimensions only (symbol, side,
 * order_type, reason, ...); account and order identifiers belong in logs and spans.
 */
public final class MetricNames {
    private static final String PREFIX = "demo.brokerage.";

    // order-service
    public static final String ORDERS_ACCEPTED = PREFIX + "orders.accepted";
    public static final String ORDERS_REJECTED = PREFIX + "orders.rejected";

    // exchange-gateway-simulator
    public static final String EXECUTIONS = PREFIX + "executions.count";
    public static final String EXECUTION_AMOUNT = PREFIX + "executions.amount_jpy";
    public static final String ORDER_TO_FIRST_EXECUTION = PREFIX + "order_to_first_execution.duration";
    public static final String ORDERS_EXPIRED = PREFIX + "orders.expired";
    public static final String MARKET_PRICE = PREFIX + "market.price";
    public static final String MARKET_PRICE_CHANGE = PREFIX + "market.price_change_pct";
    public static final String MARKET_LIMIT_DOWN = PREFIX + "market.limit_down";
    public static final String EXCHANGE_WORKING_ORDERS = PREFIX + "exchange.working_orders";

    // buying-power-service
    public static final String BUYING_POWER_RESERVATIONS = PREFIX + "buying_power.reservations";
    public static final String BUYING_POWER_UPDATES = PREFIX + "buying_power.updates";
    public static final String BUYING_POWER_REFLECTION_DELAY = PREFIX + "buying_power.reflection_delay";

    // holdings-service and trade-report-service
    public static final String HOLDINGS_UPDATES = PREFIX + "holdings.updates";
    public static final String TRADE_REPORTS_ISSUED = PREFIX + "trade_reports.issued";

    // notification-service, push-gateway-simulator, exception-management-service
    public static final String NOTIFICATIONS_DELIVERED = PREFIX + "notifications.delivered";
    public static final String NOTIFICATION_DELIVERY_DELAY = PREFIX + "notifications.delivery_delay";
    public static final String NOTIFICATION_RETRY_ATTEMPTS = PREFIX + "notifications.retry_attempts";
    public static final String NOTIFICATION_DLT_EVENTS = PREFIX + "notifications.dead_letter.events";
    public static final String NOTIFICATION_DLT_UNRESOLVED = PREFIX + "notifications.dead_letter.unresolved";
    public static final String NOTIFICATION_DLT_OLDEST_AGE = PREFIX + "notifications.dead_letter.oldest_unresolved_age";
    public static final String PUSH_REQUESTS = PREFIX + "push_gateway.requests";

    // order-state-service (current-state gauges)
    public static final String ORDERS_WORKING = PREFIX + "orders.working";
    public static final String ORDERS_UNFILLED_QUANTITY = PREFIX + "orders.unfilled_quantity";
    public static final String OLDEST_WORKING_ORDER_AGE = PREFIX + "orders.oldest_working_age";
    public static final String BUYING_POWER_UNREFLECTED = PREFIX + "buying_power.unreflected_executions";
    public static final String BUYING_POWER_OLDEST_UNREFLECTED_AGE = PREFIX + "buying_power.oldest_unreflected_age";
    public static final String NOTIFICATIONS_UNDELIVERED = PREFIX + "notifications.undelivered";

    // order-flow-simulator
    public static final String SCENARIO_CURRENT = PREFIX + "scenario.current";
    public static final String SWITCH_TRADES = PREFIX + "simulator.switch_trades";

    private MetricNames() {
    }
}
