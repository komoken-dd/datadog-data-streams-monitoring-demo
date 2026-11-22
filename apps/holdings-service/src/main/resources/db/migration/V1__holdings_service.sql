create schema if not exists holdings_service;

create table if not exists holdings_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);
