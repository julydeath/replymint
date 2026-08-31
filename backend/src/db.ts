import postgres from "postgres";

/**
 * Thin data layer over Supabase Postgres (schema in schema.sql). Plain SQL via postgres.js —
 * three tables don't need an ORM.
 *
 * `prepare: false` is required: we connect through Supabase's transaction pooler (pgbouncer),
 * which doesn't support prepared statements.
 */
let client: postgres.Sql | undefined;

function sql(): postgres.Sql {
  if (!client) {
    const url = process.env.DATABASE_URL;
    if (!url) throw new Error("DATABASE_URL is not set");
    client = postgres(url, { prepare: false, max: 5 });
  }
  return client;
}

export interface User {
  id: string;
  email: string;
  name: string | null;
  plan: "free" | "pro";
  /** Platform of the token behind this request ('android' | 'macos' | 'windows' | 'desktop');
   *  only set by userForTokenHash — absent right after sign-up, null on pre-A3 tokens. */
  platform?: string | null;
}

export async function upsertUserByGoogle(
  googleSub: string,
  email: string,
  name: string | null
): Promise<User> {
  const rows = await sql()<User[]>`
    insert into users (google_sub, email, name)
    values (${googleSub}, ${email}, ${name})
    on conflict (google_sub) do update set email = excluded.email, name = excluded.name
    returning id, email, name, plan
  `;
  const user = rows[0];
  if (!user) throw new Error("user upsert returned no row");
  return user;
}

export async function insertToken(
  tokenHash: string,
  userId: string,
  platform?: string,
  deviceName?: string
): Promise<void> {
  await sql()`
    insert into tokens (token_hash, user_id, platform, device_name)
    values (${tokenHash}, ${userId}, ${platform ?? null}, ${deviceName ?? null})
  `;
}

/** Look up the user for a token hash; touches last_used_at as a side effect. */
export async function userForTokenHash(tokenHash: string): Promise<User | null> {
  const rows = await sql()<User[]>`
    update tokens set last_used_at = now()
    from users
    where tokens.token_hash = ${tokenHash} and users.id = tokens.user_id
    returning users.id, users.email, users.name, users.plan, tokens.platform
  `;
  return rows[0] ?? null;
}

export async function deleteToken(tokenHash: string): Promise<void> {
  await sql()`delete from tokens where token_hash = ${tokenHash}`;
}

export async function todayUsage(userId: string): Promise<number> {
  const rows = await sql()<{ count: number }[]>`
    select count from usage_daily
    where user_id = ${userId} and day = (now() at time zone 'utc')::date
  `;
  return rows[0]?.count ?? 0;
}

/** Today's cloud-STT usage for this user: audio seconds proxied and sessions started (0/0 if no row). */
export async function todaySttUsage(
  userId: string
): Promise<{ seconds: number; sessions: number }> {
  const rows = await sql()<{ stt_seconds: number; stt_sessions: number }[]>`
    select stt_seconds, stt_sessions from usage_daily
    where user_id = ${userId} and day = (now() at time zone 'utc')::date
  `;
  return { seconds: rows[0]?.stt_seconds ?? 0, sessions: rows[0]?.stt_sessions ?? 0 };
}

/** Connectivity probe for /health/db — returns the error message on failure, never throws. */
export async function pingDb(): Promise<{ ok: boolean; error?: string }> {
  try {
    await sql()`select 1`;
    return { ok: true };
  } catch (e) {
    return { ok: false, error: (e as Error).message };
  }
}

/** Adds cloud-STT audio seconds to today's usage row (duration only — content is never stored). */
export async function bumpSttSeconds(userId: string, seconds: number): Promise<void> {
  if (seconds <= 0) return;
  await sql()`
    insert into usage_daily (user_id, day, count, stt_seconds)
    values (${userId}, (now() at time zone 'utc')::date, 0, ${seconds})
    on conflict (user_id, day) do update set stt_seconds = usage_daily.stt_seconds + ${seconds}
  `;
}

/** Counts a cloud-STT session against today's usage row (sessions only — content is never stored). */
export async function bumpSttSessions(userId: string): Promise<void> {
  await sql()`
    insert into usage_daily (user_id, day, count, stt_sessions)
    values (${userId}, (now() at time zone 'utc')::date, 0, 1)
    on conflict (user_id, day) do update set stt_sessions = usage_daily.stt_sessions + 1
  `;
}

export async function bumpUsage(userId: string): Promise<number> {
  const rows = await sql()<{ count: number }[]>`
    insert into usage_daily (user_id, day, count)
    values (${userId}, (now() at time zone 'utc')::date, 1)
    on conflict (user_id, day) do update set count = usage_daily.count + 1
    returning count
  `;
  return rows[0]?.count ?? 0;
}

/**
 * Website beta signups (email only, no account). The table is created lazily so a deploy
 * never has to coordinate with a manual schema migration; schema.sql documents it too.
 */
export async function insertBetaRequest(email: string, platform: string): Promise<void> {
  await sql()`
    create table if not exists beta_requests (
      email      text not null,
      platform   text not null,
      created_at timestamptz not null default now(),
      primary key (email, platform)
    )
  `;
  await sql()`
    insert into beta_requests (email, platform)
    values (${email}, ${platform})
    on conflict (email, platform) do nothing
  `;
}
