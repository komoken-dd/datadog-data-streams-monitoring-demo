create schema if not exists holdings_service;

create table if not exists holdings_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);

create table if not exists holdings_service.holdings (
    account_id varchar(16) not null,
    symbol varchar(8) not null,
    quantity bigint not null,
    average_cost numeric(12,1) not null,
    updated_at timestamptz not null,
    primary key (account_id, symbol)
);
