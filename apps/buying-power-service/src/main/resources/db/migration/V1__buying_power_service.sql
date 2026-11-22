create schema if not exists buying_power_service;

create table if not exists buying_power_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);
