create schema if not exists trade_report_service;

create table if not exists trade_report_service.processed_events (
    event_id varchar(64) primary key,
    processed_at timestamptz not null
);

create table if not exists trade_report_service.trade_reports (
    trade_report_id varchar(64) primary key,
    execution_event_id varchar(64) not null unique,
    execution_id varchar(64) not null,
    order_id varchar(64) not null,
    account_id varchar(16) not null,
    symbol varchar(8) not null,
    side varchar(4) not null,
    executed_quantity bigint not null,
    execution_price numeric(12,1) not null,
    execution_amount_jpy numeric(18,0) not null,
    commission_jpy numeric(18,0) not null,
    settlement_amount_jpy numeric(18,0) not null,
    trade_date date not null,
    settlement_date date not null,
    executed_at timestamptz not null,
    issued_at timestamptz not null,
    demo_scenario_id varchar(96)
);

create index if not exists trade_reports_account_trade_date_idx
    on trade_report_service.trade_reports(account_id, trade_date);
create index if not exists trade_reports_settlement_date_idx
    on trade_report_service.trade_reports(settlement_date);
