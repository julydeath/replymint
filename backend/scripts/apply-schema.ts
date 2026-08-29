/**
 * Applies src/schema.sql to DATABASE_URL. The schema is idempotent
 * (create table if not exists / add column if not exists), so this is safe to
 * re-run after every schema change:
 *
 *   npx tsx --env-file=.env scripts/apply-schema.ts
 */
import { readFileSync } from "node:fs";
import postgres from "postgres";

const url = process.env.DATABASE_URL;
if (!url) throw new Error("DATABASE_URL is not set (run with --env-file=.env)");

const schema = readFileSync(new URL("../src/schema.sql", import.meta.url), "utf-8");
// onnotice silences the "already exists, skipping" chatter idempotent DDL produces.
const sql = postgres(url, { prepare: false, max: 1, onnotice: () => {} });
await sql.unsafe(schema); // parameterless unsafe uses the simple protocol: multi-statement OK
console.log("schema applied");
await sql.end();
