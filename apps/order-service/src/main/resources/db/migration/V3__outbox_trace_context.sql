-- Trace metadata is independent of immutable financial payload bytes.
-- Existing rows remain valid and start a new relay trace.
alter table order_service.kafka_outbox
    add column traceparent varchar(128),
    add column tracestate varchar(512);
