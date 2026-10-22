-- Per-user, per-UTC-day Online AI request limit. No public client can read or change this table.
create table if not exists public.anb_ai_usage_daily (
  user_id uuid not null references auth.users(id) on delete cascade,
  usage_date date not null,
  request_count integer not null default 0 check (request_count >= 0),
  primary key (user_id, usage_date)
);

alter table public.anb_ai_usage_daily enable row level security;
revoke all on table public.anb_ai_usage_daily from anon, authenticated;
grant all on table public.anb_ai_usage_daily to service_role;

create or replace function public.consume_anb_ai_request(p_user_id uuid, p_daily_limit integer)
returns table (allowed boolean, remaining integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
  next_count integer;
  utc_day date := (now() at time zone 'utc')::date;
begin
  if p_user_id is null or p_daily_limit < 1 or p_daily_limit > 500 then
    return query select false, 0;
    return;
  end if;

  insert into public.anb_ai_usage_daily(user_id, usage_date, request_count)
  values (p_user_id, utc_day, 1)
  on conflict (user_id, usage_date)
  do update set request_count = public.anb_ai_usage_daily.request_count + 1
    where public.anb_ai_usage_daily.request_count < p_daily_limit
  returning public.anb_ai_usage_daily.request_count into next_count;

  if not found then
    return query select false, 0;
    return;
  end if;

  return query select true, greatest(0, p_daily_limit - next_count);
end;
$$;

revoke all on function public.consume_anb_ai_request(uuid, integer) from public, anon, authenticated;
grant execute on function public.consume_anb_ai_request(uuid, integer) to service_role;
