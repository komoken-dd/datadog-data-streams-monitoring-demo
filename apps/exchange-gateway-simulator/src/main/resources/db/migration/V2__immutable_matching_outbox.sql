create table exchange_gateway.kafka_outbox (
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
create index exchange_outbox_pending_idx on exchange_gateway.kafka_outbox (id)
    where published_at is null;
-- Retain a compact deduplication tombstone even after the detailed book row is purged.
create table exchange_gateway.accepted_order_ids (
    order_id varchar(64) primary key,
    accepted_at timestamptz not null
);
insert into exchange_gateway.accepted_order_ids (order_id, accepted_at)
    select order_id, accepted_at from exchange_gateway.working_orders on conflict do nothing;
