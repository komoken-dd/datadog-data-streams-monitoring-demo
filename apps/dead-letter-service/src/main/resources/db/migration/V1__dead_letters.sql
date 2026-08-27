create schema if not exists dead_letter_service;

create table if not exists dead_letter_service.dead_letters (
    event_id varchar(64) primary key,
    order_id varchar(64) not null,
    currency_pair varchar(7) not null,
    side varchar(4) not null,
    quantity numeric(19,2) not null,
    limit_price numeric(19,6) not null,
    notional_usd numeric(19,2) not null,
    submitted_at timestamptz not null,
    approved_at timestamptz not null,
    demo_scenario_id varchar(96),
    retry_count integer not null,
    failure_category varchar(64) not null,
    failure_timestamp timestamptz not null,
    originating_topic varchar(128) not null,
    received_at timestamptz not null,
    resolved_at timestamptz,
    redrive_event_id varchar(64)
);

create index if not exists dead_letters_unresolved_idx
    on dead_letter_service.dead_letters(demo_scenario_id) where resolved_at is null;

