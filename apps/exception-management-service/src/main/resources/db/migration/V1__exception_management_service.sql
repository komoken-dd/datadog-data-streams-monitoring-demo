create schema if not exists exception_management_service;

create table if not exists exception_management_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);

-- One row per notification request that exhausted its retries. A redriven request that fails
-- again arrives with a new event_id and becomes a new row.
create table if not exists exception_management_service.notification_dead_letters (
    event_id varchar(64) primary key,
    notification_id varchar(64) not null,
    account_id varchar(64) not null,
    order_id varchar(64) not null,
    symbol varchar(16) not null,
    side varchar(4) not null,
    notification_type varchar(16) not null,
    quantity bigint not null,
    price numeric(12,1),
    source_event_id varchar(64) not null,
    source_occurred_at timestamptz not null,
    demo_scenario_id varchar(96),
    retry_count integer not null,
    failure_category varchar(64) not null,
    failure_timestamp timestamptz not null,
    originating_topic varchar(128) not null,
    received_at timestamptz not null,
    resolved_at timestamptz,
    redrive_event_id varchar(64)
);

create index if not exists notification_dead_letters_unresolved_idx
    on exception_management_service.notification_dead_letters (received_at) where resolved_at is null;
