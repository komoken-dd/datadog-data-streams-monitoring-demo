package io.github.komokendd.dsm.fx.common;

public final class MetricNames {
    public static final String ORDERS_RECEIVED = "demo.fx.orders.received";
    public static final String ORDERS_PENDING = "demo.fx.orders.pending";
    public static final String ORDERS_PENDING_NOTIONAL = "demo.fx.orders.pending_notional_usd";
    public static final String ORDERS_PENDING_BY_PAIR = "demo.fx.orders.pending_by_pair";
    public static final String ORDERS_PENDING_NOTIONAL_BY_PAIR = "demo.fx.orders.pending_notional_by_pair_usd";
    public static final String ORDERS_PENDING_EXPECTED_FEE = "demo.fx.orders.pending_expected_fee_usd";
    public static final String ORDERS_REJECTED = "demo.fx.orders.rejected";
    public static final String OLDEST_PENDING_AGE = "demo.fx.orders.oldest_pending_age";
    public static final String EXECUTIONS_COMPLETED = "demo.fx.executions.completed";
    public static final String EXECUTIONS_NOTIONAL = "demo.fx.executions.notional_usd";
    public static final String EXECUTIONS_SYNTHETIC_FEE = "demo.fx.executions.synthetic_fee_usd";
    public static final String ORDER_TO_EXECUTION = "demo.fx.order_to_execution.duration";
    public static final String RETRY_ATTEMPTS = "demo.fx.retry.attempts";
    public static final String DLT_EVENTS = "demo.fx.dead_letter.events";
    public static final String DLT_UNRESOLVED = "demo.fx.dead_letter.unresolved";
    public static final String DLT_OLDEST_UNRESOLVED_AGE = "demo.fx.dead_letter.oldest_unresolved_age";
    public static final String DLT_NOTIONAL = "demo.fx.dead_letter.notional_usd";
    public static final String SCENARIO_CURRENT = "demo.fx.scenario.current";

    private MetricNames() {
    }
}
