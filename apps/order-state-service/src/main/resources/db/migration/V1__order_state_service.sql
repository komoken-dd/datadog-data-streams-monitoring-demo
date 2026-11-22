create schema if not exists order_state_service;

create table if not exists order_state_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);
