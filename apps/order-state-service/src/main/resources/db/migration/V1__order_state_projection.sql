create schema if not exists order_state_service;

create table if not exists order_state_service.processed_transitions (
    event_id varchar(64) not null,
    transition_key varchar(64) not null,
    processed_at timestamptz not null,
    primary key (event_id, transition_key)
);

create table if not exists order_state_service.orders (
    order_id varchar(64) primary key,
    status varchar(32) not null,
    status_rank integer not null,
    currency_pair varchar(7) not null,
    side varchar(4) not null,
    notional_usd numeric(19,2) not null,
    submitted_at timestamptz not null,
    updated_at timestamptz not null,
    demo_scenario_id varchar(96)
);

create index if not exists orders_pending_idx on order_state_service.orders(status, submitted_at);
create index if not exists orders_scenario_idx on order_state_service.orders(demo_scenario_id);

