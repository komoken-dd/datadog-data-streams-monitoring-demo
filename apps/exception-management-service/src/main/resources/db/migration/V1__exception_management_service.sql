create schema if not exists exception_management_service;

create table if not exists exception_management_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);
