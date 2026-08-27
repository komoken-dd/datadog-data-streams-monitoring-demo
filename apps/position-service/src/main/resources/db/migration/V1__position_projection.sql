create schema if not exists position_service;

create table if not exists position_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);

create table if not exists position_service.net_positions (
    currency_pair varchar(7) primary key,
    net_quantity numeric(19,2) not null,
    updated_at timestamptz not null
);

