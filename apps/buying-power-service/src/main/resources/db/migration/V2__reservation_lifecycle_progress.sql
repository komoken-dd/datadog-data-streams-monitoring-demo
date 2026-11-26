-- Track locally reflected fills independently of terminal exchange messages.
-- Older RESERVED rows can be upgraded from their proportional released hold.
alter table buying_power_service.reservations
    add column reflected_quantity bigint not null default 0,
    add column expired_quantity bigint;
update buying_power_service.reservations
set reflected_quantity = case
    when status = 'CONSUMED' then quantity
    when side = 'SELL' then released_quantity
    when reserved_amount_jpy > 0 then ceil(quantity * released_amount_jpy / reserved_amount_jpy)::bigint
    else 0 end;
-- Closed historical rows retain their old semantics; existing unresolved/unknown
-- reservations must be reconciled, never bulk-released during this migration.
alter table buying_power_service.reservations
    add constraint reservation_reflected_quantity_valid check (reflected_quantity between 0 and quantity),
    add constraint reservation_expired_quantity_valid check (expired_quantity is null or
        (expired_quantity >= 0 and reflected_quantity + expired_quantity <= quantity));
