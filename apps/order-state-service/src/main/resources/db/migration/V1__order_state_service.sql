create schema if not exists order_state_service;

create table if not exists order_state_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);

create table if not exists order_state_service.processed_transitions (
    event_id varchar(64) not null,
    transition_key varchar(64) not null,
    processed_at timestamptz not null,
    primary key (event_id, transition_key)
);

create table if not exists order_state_service.orders (
    order_id varchar(64) primary key,
    account_id varchar(16) not null,
    symbol varchar(8) not null,
    side varchar(4) not null,
    order_type varchar(8) not null,
    quantity bigint not null,
    cumulative_quantity bigint not null,
    status varchar(24) not null,
    status_rank integer not null,
    accepted_at timestamptz not null,
    updated_at timestamptz not null,
    demo_scenario_id varchar(96)
);

-- Working-order gauges scan only orders still at the exchange.
create index if not exists orders_working_idx
    on order_state_service.orders(symbol, side, accepted_at)
    where status in ('ACCEPTED', 'PARTIALLY_FILLED');
create index if not exists orders_scenario_idx on order_state_service.orders(demo_scenario_id);

create table if not exists order_state_service.executions (
    execution_event_id varchar(64) primary key,
    execution_id varchar(64) not null,
    order_id varchar(64) not null,
    account_id varchar(16) not null,
    symbol varchar(8) not null,
    side varchar(4) not null,
    sequence integer not null,
    quantity bigint not null,
    price numeric(12,1) not null,
    executed_at timestamptz not null,
    buying_power_reflected_at timestamptz,
    trade_report_issued_at timestamptz,
    notification_status varchar(16) not null,
    notification_delivered_at timestamptz
);

create index if not exists executions_order_idx on order_state_service.executions(order_id, sequence);
create index if not exists executions_execution_id_idx on order_state_service.executions(execution_id);
create index if not exists executions_buying_power_unreflected_idx
    on order_state_service.executions(executed_at)
    where buying_power_reflected_at is null;
create index if not exists executions_notification_undelivered_idx
    on order_state_service.executions(executed_at)
    where notification_status <> 'DELIVERED';

-- Downstream outcomes that overtook their execution report; applied when the execution arrives.
create table if not exists order_state_service.pending_execution_marks (
    id bigserial primary key,
    order_id varchar(64) not null,
    match_key varchar(24) not null,
    match_value varchar(64) not null,
    mark_type varchar(32) not null,
    marked_at timestamptz,
    redriven boolean not null,
    received_at timestamptz not null
);

create index if not exists pending_execution_marks_match_idx
    on order_state_service.pending_execution_marks(order_id, match_value);
