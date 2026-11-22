create schema if not exists buying_power_service;

create table if not exists buying_power_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);

-- buying power = cash_jpy - reserved_jpy
create table if not exists buying_power_service.accounts (
    account_id varchar(32) primary key,
    cash_jpy numeric(18, 0) not null,
    reserved_jpy numeric(18, 0) not null default 0,
    updated_at timestamptz not null default now()
);

-- sellable quantity = quantity - reserved_quantity
create table if not exists buying_power_service.sellable (
    account_id varchar(32) not null references buying_power_service.accounts (account_id),
    symbol varchar(16) not null,
    quantity bigint not null,
    reserved_quantity bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (account_id, symbol)
);

create table if not exists buying_power_service.reservations (
    order_id varchar(64) primary key,
    account_id varchar(32) not null,
    symbol varchar(16) not null,
    side varchar(8) not null,
    order_type varchar(8) not null,
    quantity bigint not null,
    reserved_amount_jpy numeric(18, 0) not null,
    released_amount_jpy numeric(18, 0) not null default 0,
    reserved_quantity bigint not null,
    released_quantity bigint not null default 0,
    status varchar(16) not null check (status in ('RESERVED', 'RELEASED', 'CONSUMED')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists reservations_account_status_idx
    on buying_power_service.reservations (account_id, status);
