-- Do not remove these while a corresponding submission may still be retried.
create table buying_power_service.reservation_cancellations (
    order_id varchar(64) primary key,
    cancelled_at timestamptz not null
);
