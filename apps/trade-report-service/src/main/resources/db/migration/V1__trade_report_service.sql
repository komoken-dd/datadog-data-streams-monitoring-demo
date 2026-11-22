create schema if not exists trade_report_service;

create table if not exists trade_report_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);
