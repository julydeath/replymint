/**
 * Mints an rt_ token for a persistent local dev user — the desktop app has no
 * sign-in yet (Backlog A3), so paste this into its Settings, or export it as
 * REPLYMINT_TOKEN for `replymint-desktop smoke`.
 *
 *   npx tsx --env-file=.env scripts/dev-token.ts
 *
 * Reuses one "dev-local" user per DB, so re-running just adds a token for it.
 * Revoke everything by deleting the user row (tokens cascade).
 */
import { createHash, randomBytes } from "node:crypto";
import postgres from "postgres";

const url = process.env.DATABASE_URL;
if (!url) throw new Error("DATABASE_URL is not set (run with --env-file=.env)");
const sql = postgres(url, { prepare: false, max: 1 });

// Pro plan: the desktop app's cloud STT is pro-gated, and this token exists to test it.
const [user] = await sql<{ id: string }[]>`
  insert into users (google_sub, email, name, plan)
  values ('dev-local', 'dev-local@test.invalid', 'Local Dev', 'pro')
  on conflict (google_sub) do update set email = excluded.email, plan = 'pro'
  returning id
`;
if (!user) throw new Error("failed to upsert dev user");

const token = "rt_" + randomBytes(32).toString("base64url");
const hash = createHash("sha256").update(token).digest("hex");
await sql`insert into tokens (token_hash, user_id) values (${hash}, ${user.id})`;
await sql.end();

console.log(token);
