-- Retain replay identity within one finite demo epoch; never delete dedupe rows independently.
create table order_service.epoch_budget (
    singleton boolean primary key default true check (singleton),
    submission_count bigint not null check (submission_count >= 0),
    max_submissions bigint not null check (max_submissions > 0)
);
insert into order_service.epoch_budget (singleton, submission_count, max_submissions)
    select true, count(*), 50000 from order_service.submissions;
-- An AFTER INSERT trigger runs only for a newly inserted logical order, not an
-- ON CONFLICT retry. PostgreSQL rechecks the conditional update after concurrent
-- row-lock waits, so the persistent bound cannot be exceeded by parallel callers.
create function order_service.enforce_epoch_budget() returns trigger language plpgsql security definer
    set search_path = pg_catalog, order_service as '
begin
    update order_service.epoch_budget set submission_count = submission_count + 1
        where singleton = true and submission_count < max_submissions;
    if not found then
        raise exception ''DEMO_EPOCH_LIMIT_REACHED'' using errcode = ''P0001'';
    end if;
    return new;
end';
create trigger bounded_demo_epoch after insert on order_service.submissions
    for each row execute function order_service.enforce_epoch_budget();
