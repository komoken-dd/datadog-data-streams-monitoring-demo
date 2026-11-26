create schema if not exists order_service;
create table order_service.submissions (
    order_id varchar(64) primary key,
    account_id varchar(32) not null,
    request_key varchar(128) not null,
    request_hash varchar(64) not null,
    event_id varchar(64) not null unique,
    event_payload bytea not null,
    reservation_deadline timestamptz not null,
    state varchar(32) not null check (state in ('RESERVING','UNKNOWN','ACCEPTED','REJECTED','CANCELLED')),
    failure_reason varchar(64),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (account_id, request_key)
);
create index submissions_pending_idx on order_service.submissions (created_at)
    where state in ('RESERVING','UNKNOWN');
create table order_service.kafka_outbox (
    id bigserial primary key,
    event_id varchar(64) not null unique,
    topic varchar(128) not null,
    record_key varchar(64) not null,
    order_id varchar(64) not null,
    event_type varchar(64) not null,
    symbol varchar(16) not null,
    demo_scenario_id varchar(128),
    payload bytea not null,
    created_at timestamptz not null,
    published_at timestamptz
);
create index order_outbox_pending_idx on order_service.kafka_outbox (id) where published_at is null;
