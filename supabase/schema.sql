-- Pesa Board licensing for Supabase. Run this whole file once in the SQL editor.
-- Tables live in the "private" schema, which the Supabase API does not expose.
-- The app can only call the two functions at the bottom (activate_license, redeem_topup).

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create table if not exists private.licenses (
  code         text primary key,
  device_id    text,
  note         text,
  revoked      boolean not null default false,
  created_at   timestamptz not null default now(),
  activated_at timestamptz
);

create table if not exists private.topups (
  code            text primary key,
  license_code    text not null references private.licenses(code),
  amount_cents    bigint not null check (amount_cents > 0),
  created_at      timestamptz not null default now(),
  redeemed_at     timestamptz,
  redeemed_device text
);

create table if not exists private.failures (
  device_id text not null,
  at        timestamptz not null default now()
);
create index if not exists failures_device_at on private.failures (device_id, at);

alter table private.licenses enable row level security;
alter table private.topups   enable row level security;
alter table private.failures enable row level security;

-- ---------- helpers ----------

-- "abcd efgh-jklm" -> "ABCD-EFGH-JKLM"; null if it isn't 12 letters/digits.
create or replace function private.fmt(p text) returns text
language sql immutable as $$
  select case when length(n) = 12
    then substr(n,1,4) || '-' || substr(n,5,4) || '-' || substr(n,9,4) end
  from (select upper(regexp_replace(coalesce(p,''), '[^A-Za-z0-9]', '', 'g')) as n) s
$$;

-- 32 characters, no 0/O/1/I; 12 random characters = 60 bits.
create or replace function private.new_code() returns text
language plpgsql as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  bytes bytea := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
  s text := '';
  i int;
begin
  for i in 0..11 loop
    s := s || substr(alphabet, (get_byte(bytes, i) % 32) + 1, 1);
  end loop;
  return substr(s,1,4) || '-' || substr(s,5,4) || '-' || substr(s,9,4);
end $$;

create or replace function private.blocked(p_device text) returns boolean
language sql stable as $$
  select count(*) >= 10 from private.failures
  where device_id = p_device and at > now() - interval '15 minutes'
$$;

create or replace function private.log_failure(p_device text) returns void
language plpgsql as $$
begin
  delete from private.failures where at < now() - interval '1 day';
  insert into private.failures (device_id) values (p_device);
end $$;

-- ---------- admin (you, from the SQL editor) ----------

-- select * from private.create_licenses(5, 'batch 1');
create or replace function private.create_licenses(p_count int default 1, p_note text default '')
returns setof text language plpgsql as $$
declare c text; i int;
begin
  for i in 1..least(greatest(p_count,1),100) loop
    c := private.new_code();
    insert into private.licenses (code, note) values (c, left(p_note,100));
    return next c;
  end loop;
end $$;

-- select * from private.create_topups('ABCD-EFGH-JKLM', 25000);   -- Ksh of game money
create or replace function private.create_topups(p_license text, p_amount numeric, p_count int default 1)
returns setof text language plpgsql as $$
declare lic text := private.fmt(p_license); c text; i int;
begin
  if lic is null or not exists (select 1 from private.licenses where code = lic) then
    raise exception 'Unknown license code';
  end if;
  if p_amount is null or p_amount <= 0 or p_amount > 100000000 then
    raise exception 'Invalid amount';
  end if;
  for i in 1..least(greatest(p_count,1),50) loop
    c := private.new_code();
    insert into private.topups (code, license_code, amount_cents)
    values (c, lic, round(p_amount * 100));
    return next c;
  end loop;
end $$;

-- Buyer got a new phone:  select private.unbind('ABCD-EFGH-JKLM');
create or replace function private.unbind(p_license text) returns boolean
language sql as $$
  with u as (update private.licenses set device_id = null, activated_at = null
             where code = private.fmt(p_license) returning 1)
  select exists (select 1 from u)
$$;

-- select private.revoke_license('ABCD-EFGH-JKLM');
create or replace function private.revoke_license(p_license text) returns boolean
language sql as $$
  with u as (update private.licenses set revoked = true
             where code = private.fmt(p_license) returning 1)
  select exists (select 1 from u)
$$;

-- select * from private.overview;
create or replace view private.overview as
select l.code, l.note, l.revoked, l.created_at, l.activated_at,
       (l.device_id is not null) as bound,
       coalesce(sum(t.amount_cents) filter (where t.redeemed_at is not null), 0) / 100.0 as redeemed_ksh,
       coalesce(sum(t.amount_cents) filter (where t.redeemed_at is null), 0) / 100.0 as pending_ksh
from private.licenses l left join private.topups t on t.license_code = l.code
group by l.code order by l.created_at desc;

-- ---------- app-facing functions (the only thing the phone can call) ----------

create or replace function public.activate_license(p_code text, p_device text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare k text := private.fmt(p_code); lic private.licenses%rowtype;
begin
  if k is null or p_device is null or length(p_device) not between 8 and 128 then
    return jsonb_build_object('ok', false, 'error', 'Enter a valid activation code');
  end if;
  if private.blocked(p_device) then
    return jsonb_build_object('ok', false, 'error', 'Too many attempts. Try again in a few minutes.');
  end if;

  select * into lic from private.licenses where code = k for update;
  if not found or lic.revoked then
    perform private.log_failure(p_device);
    return jsonb_build_object('ok', false, 'error', 'That code is not valid');
  end if;
  if lic.device_id is not null and lic.device_id <> p_device then
    perform private.log_failure(p_device);
    return jsonb_build_object('ok', false, 'error', 'This code is already used on another phone');
  end if;
  if lic.device_id is null then
    update private.licenses set device_id = p_device, activated_at = now() where code = k;
  end if;
  return jsonb_build_object('ok', true, 'licenseCode', k);
end $$;

create or replace function public.redeem_topup(p_license text, p_code text, p_device text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  lk text := private.fmt(p_license);
  tk text := private.fmt(p_code);
  lic private.licenses%rowtype;
  t private.topups%rowtype;
begin
  if lk is null or tk is null or p_device is null or length(p_device) not between 8 and 128 then
    return jsonb_build_object('ok', false, 'error', 'Enter a valid top-up code');
  end if;
  if private.blocked(p_device) then
    return jsonb_build_object('ok', false, 'error', 'Too many attempts. Try again in a few minutes.');
  end if;

  select * into lic from private.licenses where code = lk for update;
  if not found or lic.revoked or lic.device_id is distinct from p_device then
    perform private.log_failure(p_device);
    return jsonb_build_object('ok', false, 'error', 'This phone is not activated');
  end if;

  select * into t from private.topups where code = tk and license_code = lk for update;
  if not found then
    perform private.log_failure(p_device);
    return jsonb_build_object('ok', false, 'error', 'That code is not valid');
  end if;
  if t.redeemed_at is not null then
    -- Same phone retrying after a lost response: tell the app so it can recover.
    if t.redeemed_device = p_device then
      return jsonb_build_object('ok', true, 'amountCents', t.amount_cents, 'replay', true);
    end if;
    return jsonb_build_object('ok', false, 'error', 'That code has already been used');
  end if;

  update private.topups set redeemed_at = now(), redeemed_device = p_device where code = tk;
  return jsonb_build_object('ok', true, 'amountCents', t.amount_cents);
end $$;

revoke execute on function public.activate_license(text, text) from public;
revoke execute on function public.redeem_topup(text, text, text) from public;
grant  execute on function public.activate_license(text, text) to anon, authenticated;
grant  execute on function public.redeem_topup(text, text, text) to anon, authenticated;
