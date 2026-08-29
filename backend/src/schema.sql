-- ReplyMint schema. Run once in the Supabase SQL editor (idempotent).

create table if not exists users (
  id         uuid primary key default gen_random_uuid(),
  google_sub text unique not null,
  email      text not null,
  name       text,
  created_at timestamptz not null default now()
);

create table if not exists tokens (
  token_hash   text primary key,              -- sha256 hex of the opaque rt_ token
  user_id      uuid not null references users(id) on delete cascade,
  created_at   timestamptz not null default now(),
  last_used_at timestamptz
);

create table if not exists usage_daily (
  user_id uuid not null references users(id) on delete cascade,
  day     date not null default (now() at time zone 'utc')::date,
  count   int  not null default 0,
  primary key (user_id, day)
);

-- V3 cloud STT metering: seconds of audio proxied per day (content is never stored).
alter table usage_daily add column if not exists stt_seconds int not null default 0;
