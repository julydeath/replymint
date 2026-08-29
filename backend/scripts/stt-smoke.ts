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

// The main smoke user is pro — /v1/stt/stream is pro-gated.
const [user] = await sql<{ id: string }[]>`
  insert into users (google_sub, email, name, plan)
  values ('smoke-test-stt', 'stt-smoke@test.invalid', 'STT Smoke', 'pro')
  on conflict (google_sub) do update set email = excluded.email, plan = 'pro'
  returning id
`;
if (!user) throw new Error("failed to create smoke user");
const token = "rt_" + randomBytes(32).toString("base64url");
await sql`insert into tokens (token_hash, user_id) values (${sha256(token)}, ${user.id})`;

// A second, free-plan user to assert the pro gate.
const [freeUser] = await sql<{ id: string }[]>`
  insert into users (google_sub, email, name, plan)
  values ('smoke-test-stt-free', 'stt-smoke-free@test.invalid', 'STT Smoke Free', 'free')
  on conflict (google_sub) do update set email = excluded.email, plan = 'free'
  returning id
`;
if (!freeUser) throw new Error("failed to create free smoke user");
const freeToken = "rt_" + randomBytes(32).toString("base64url");
await sql`insert into tokens (token_hash, user_id) values (${sha256(freeToken)}, ${freeUser.id})`;

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

  // 4 — a free-plan user must be turned away with a typed pro_required error + close 4403.
  await new Promise<void>((resolve, reject) => {
    const ws = new WebSocket(`${BASE}/v1/stt/stream`, {
      headers: { Authorization: `Bearer ${freeToken}` },
    });
    let sawProRequired = false;
    ws.on("message", (d) => {
      const msg = JSON.parse(d.toString());
      sawProRequired = msg.type === "error" && msg.code === "pro_required";
      console.log("<- (free)", d.toString());
    });
    ws.on("close", (code) => {
      if (code === 4403 && sawProRequired) {
        console.log("free user rejected as expected: pro_required, close", code);
      } else {
        console.error(`FAIL: free user close=${code}, pro_required frame=${sawProRequired}`);
      }
      resolve();
    });
    ws.on("error", reject);
  });
} finally {
  await sql`delete from users where id = ${user.id}`;
  await sql`delete from users where id = ${freeUser.id}`;
  console.log("smoke users cleaned up");
  await sql.end();
}
