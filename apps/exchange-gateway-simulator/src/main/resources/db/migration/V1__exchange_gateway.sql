create schema if not exists exchange_gateway;

create table if not exists exchange_gateway.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);
