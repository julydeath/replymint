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

// The main smoke user is pro — unlimited cloud-STT sessions.
const [user] = await sql<{ id: string }[]>`
  insert into users (google_sub, email, name, plan)
  values ('smoke-test-stt', 'stt-smoke@test.invalid', 'STT Smoke', 'pro')
  on conflict (google_sub) do update set email = excluded.email, plan = 'pro'
  returning id
`;
if (!user) throw new Error("failed to create smoke user");
const token = "rt_" + randomBytes(32).toString("base64url");
await sql`insert into tokens (token_hash, user_id) values (${sha256(token)}, ${user.id})`;

// A second, free-plan user to assert the beta free-tier gates (desktop allowance,
// daily session limit, android denial).
const [freeUser] = await sql<{ id: string }[]>`
  insert into users (google_sub, email, name, plan)
  values ('smoke-test-stt-free', 'stt-smoke-free@test.invalid', 'STT Smoke Free', 'free')
  on conflict (google_sub) do update set email = excluded.email, plan = 'free'
  returning id
`;
if (!freeUser) throw new Error("failed to create free smoke user");
const freeToken = "rt_" + randomBytes(32).toString("base64url");
await sql`
  insert into tokens (token_hash, user_id, platform)
  values (${sha256(freeToken)}, ${freeUser.id}, 'windows')
`;
const freeAndroidToken = "rt_" + randomBytes(32).toString("base64url");
await sql`
  insert into tokens (token_hash, user_id, platform)
  values (${sha256(freeAndroidToken)}, ${freeUser.id}, 'android')
`;

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

  // 4 — a free DESKTOP user gets the beta allowance: admitted, and the session is counted.
  await new Promise<void>((resolve, reject) => {
    const ws = new WebSocket(`${BASE}/v1/stt/stream`, {
      headers: { Authorization: `Bearer ${freeToken}` },
    });
    ws.on("open", () => {
      ws.send(JSON.stringify({ type: "config", sampleRate: 16000 }));
      ws.send(Buffer.alloc(8000)); // 250ms of silence — enough to open a session
      ws.send(JSON.stringify({ type: "finish" }));
    });
    ws.on("message", (d) => console.log("<- (free desktop)", d.toString()));
    ws.on("close", (code) => {
      console.log(code < 4000 ? "free desktop admitted, close" : "FAIL: free desktop denied, close", code);
      resolve();
    });
    ws.on("error", reject);
  });
  await new Promise((r) => setTimeout(r, 500));
  const freeUsage = await sql`
    select stt_sessions from usage_daily where user_id = ${freeUser.id}
  `;
  console.log(
    (freeUsage[0]?.stt_sessions ?? 0) >= 1 ? "session counted:" : "FAIL: session not counted:",
    freeUsage
  );

  // 5 — the same free desktop user over the daily session limit → stt_quota, close 4429.
  await sql`update usage_daily set stt_sessions = 100000 where user_id = ${freeUser.id}`;
  await new Promise<void>((resolve, reject) => {
    const ws = new WebSocket(`${BASE}/v1/stt/stream`, {
      headers: { Authorization: `Bearer ${freeToken}` },
    });
    let sawQuota = false;
    ws.on("message", (d) => {
      const msg = JSON.parse(d.toString());
      sawQuota = msg.type === "error" && msg.code === "stt_quota";
      console.log("<- (free over limit)", d.toString());
    });
    ws.on("close", (code) => {
      if (code === 4429 && sawQuota) {
        console.log("free user over limit rejected as expected: stt_quota, close", code);
      } else {
        console.error(`FAIL: over-limit free user close=${code}, stt_quota frame=${sawQuota}`);
      }
      resolve();
    });
    ws.on("error", reject);
  });

  // 6 — a free ANDROID token must be turned away with pro_required + close 4403
  // (checked before the quota read — the android denial wins even over limit).
  await new Promise<void>((resolve, reject) => {
    const ws = new WebSocket(`${BASE}/v1/stt/stream`, {
      headers: { Authorization: `Bearer ${freeAndroidToken}` },
    });
    let sawProRequired = false;
    ws.on("message", (d) => {
      const msg = JSON.parse(d.toString());
      sawProRequired = msg.type === "error" && msg.code === "pro_required";
      console.log("<- (free android)", d.toString());
    });
    ws.on("close", (code) => {
      if (code === 4403 && sawProRequired) {
        console.log("free android user rejected as expected: pro_required, close", code);
      } else {
        console.error(`FAIL: free android close=${code}, pro_required frame=${sawProRequired}`);
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
