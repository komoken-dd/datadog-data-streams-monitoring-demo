create schema if not exists exchange_gateway;

create table if not exists exchange_gateway.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);

-- Order book of the simulated exchange. Rows are inserted from brokerage.orders.accepted.v1
-- and advanced only by the single-threaded matching tick.
create table if not exists exchange_gateway.working_orders (
    order_id varchar(64) primary key,
    event_id varchar(64) not null,
    account_id varchar(32) not null,
    symbol varchar(16) not null,
    side varchar(8) not null,
    order_type varchar(8) not null,
    quantity bigint not null,
    limit_price numeric(12, 1),
    reserved_amount_jpy numeric(18, 0) not null,
    demo_scenario_id varchar(128),
    cumulative_quantity bigint not null default 0,
    next_execution_sequence integer not null default 1,
    status varchar(16) not null default 'WORKING',
    accepted_at timestamptz not null,
    first_executed_at timestamptz,
    received_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists working_orders_working_idx
    on exchange_gateway.working_orders (accepted_at, order_id)
    where status = 'WORKING';

create index if not exists working_orders_completed_idx
    on exchange_gateway.working_orders (updated_at)
    where status <> 'WORKING';
