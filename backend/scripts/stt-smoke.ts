/**
 * Dev smoke test for the /v1/stt/stream proxy (VOICE_PLAN V3). Run against a
 * locally running server:
 *
 *   npx tsx --env-file=.env scripts/stt-smoke.ts [audio.raw]
 *
 * With no argument it streams 3s of PCM16 silence — enough to verify the
 * protocol end-to-end with STT_PROVIDER=mock. Pass a raw 16kHz mono PCM16 file
 * to hear back a real Deepgram transcript.
 *
 * It creates a throwaway user + token directly in the DB (auth needs one) and
 * deletes the user again at the end (tokens/usage rows cascade).
 */
import { createHash, randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import postgres from "postgres";
import WebSocket from "ws";

const BASE = process.env.SMOKE_BASE_URL ?? "ws://localhost:8787";
const url = process.env.DATABASE_URL;
if (!url) throw new Error("DATABASE_URL is not set (run with --env-file=.env)");
const sql = postgres(url, { prepare: false, max: 2 });
const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");

const [user] = await sql<{ id: string }[]>`
  insert into users (google_sub, email, name)
  values ('smoke-test-stt', 'stt-smoke@test.invalid', 'STT Smoke')
  on conflict (google_sub) do update set email = excluded.email
  returning id
`;
if (!user) throw new Error("failed to create smoke user");
const token = "rt_" + randomBytes(32).toString("base64url");
await sql`insert into tokens (token_hash, user_id) values (${sha256(token)}, ${user.id})`;

try {
  // 1 — no token must be rejected at the upgrade.
  await new Promise<void>((resolve) => {
    const ws = new WebSocket(`${BASE}/v1/stt/stream`);
    ws.on("error", (e) => {
      console.log("no-auth rejected as expected:", e.message);
      resolve();
    });
    ws.on("open", () => {
      console.error("FAIL: unauthenticated upgrade was accepted");
      ws.close();
      resolve();
    });
  });

  // 2 — authenticated session: config → audio → finish → done.
  const audio = process.argv[2]
    ? readFileSync(process.argv[2])
    : Buffer.alloc(16000 * 2 * 3); // 3s of silence
  await new Promise<void>((resolve, reject) => {
    const ws = new WebSocket(`${BASE}/v1/stt/stream`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    ws.on("open", () => {
      ws.send(JSON.stringify({ type: "config", sampleRate: 16000, keywords: ["Sanjay"] }));
      // ~250ms frames, the shape AudioPipeline will send.
      for (let off = 0; off < audio.length; off += 8000) {
        ws.send(audio.subarray(off, off + 8000));
      }
      ws.send(JSON.stringify({ type: "finish" }));
    });
    ws.on("message", (d) => console.log("<-", d.toString()));
    ws.on("close", (code) => {
      console.log("closed with code", code);
      resolve();
    });
    ws.on("error", reject);
  });

  // 3 — metering landed (written on close, so give the server a beat).
  await new Promise((r) => setTimeout(r, 500));
  const usage = await sql`
    select count, stt_seconds from usage_daily where user_id = ${user.id}
  `;
  console.log("usage_daily:", usage);
} finally {
  await sql`delete from users where id = ${user.id}`;
  console.log("smoke user cleaned up");
  await sql.end();
}
