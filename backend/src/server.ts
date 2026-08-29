import { serve } from "@hono/node-server";
import { createNodeWebSocket } from "@hono/node-ws";
import { Hono } from "hono";
import { exchangeGoogleToken, requireAuth, signOut, type AuthEnv } from "./auth.js";
import { bumpSttSeconds, bumpUsage, pingDb, todayUsage } from "./db.js";
import { generateReply } from "./llm.js";
import { buildSystem, buildUser } from "./prompts.js";
import { openSttSession, type SttSession } from "./stt.js";
import { ReplyRequestSchema, SttConfigSchema, SttControlSchema, type SttConfig } from "./types.js";

const app = new Hono<AuthEnv>();
const { injectWebSocket, upgradeWebSocket } = createNodeWebSocket({ app });

const FREE_DAILY_LIMIT = Number(process.env.FREE_DAILY_LIMIT ?? 50);

// Accounts that skip the free-tier cap (comma-separated emails; devs/testers). Their usage is
// still counted, just never blocked.
const exemptEmails = new Set(
  (process.env.FREE_LIMIT_EXEMPT_EMAILS ?? "")
    .split(",")
    .map((e) => e.trim().toLowerCase())
    .filter(Boolean)
);
const isExempt = (email: string) => exemptEmails.has(email.toLowerCase());

app.get("/health", (c) => c.json({ ok: true }));

// DB connectivity probe (error message only, never credentials) — for diagnosing deploys.
app.get("/health/db", async (c) => {
  const result = await pingDb();
  return c.json(result, result.ok ? 200 : 500);
});

app.post("/v1/auth/google", exchangeGoogleToken);
app.post("/v1/auth/signout", signOut);

/** Feeds the home-screen usage card; also serves as a warm-up ping on app open. */
app.get("/v1/me", requireAuth, async (c) => {
  const user = c.var.user;
  return c.json({
    email: user.email,
    name: user.name,
    todayCount: await todayUsage(user.id),
    dailyLimit: FREE_DAILY_LIMIT,
  });
});

/**
 * POST /v1/reply — the whole product in one endpoint.
 * Body: { mode, action, screen, voiceInstruction?, brain? } -> { draft }
 *
 * Privacy: personal mode is stateless (nothing stored); professional mode uses
 * the Reply Brain but we never persist raw screen text here. We also never log
 * prompt contents — only error class/message. Usage metering stores a per-day
 * counter only, never content.
 */
app.post("/v1/reply", requireAuth, async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "invalid json" }, 400);
  }

  const parsed = ReplyRequestSchema.safeParse(body);
  if (!parsed.success) {
    return c.json({ error: "invalid request", details: parsed.error.flatten() }, 422);
  }
  const req = parsed.data;

  // Free-tier gate: check before spending an LLM call, increment only after success.
  const user = c.var.user;
  if (!isExempt(user.email) && (await todayUsage(user.id)) >= FREE_DAILY_LIMIT) {
    return c.json({ error: "daily limit reached" }, 429);
  }

  // Personal mode never uses a brain, even if one is sent.
  const brain = req.mode === "professional" ? req.brain : undefined;

  try {
    const draft = await generateReply({
      mode: req.mode,
      system: buildSystem(req.mode),
      user: buildUser(req, brain),
    });
    if (!draft) return c.json({ error: "empty draft" }, 502);
    await bumpUsage(user.id);
    return c.json({ draft });
  } catch (e) {
    console.error("reply generation failed:", (e as Error).message);
    return c.json({ error: "generation failed" }, 502);
  }
});

// A voice instruction is ~10s; 5 minutes of audio per session is a generous ceiling
// that still stops a stuck client from streaming (and paying for) audio forever.
const MAX_AUDIO_SECONDS = 300;

/**
 * GET /v1/stt/stream — WebSocket (VOICE_PLAN V3): the cloud STT proxy.
 * Device → us → Deepgram; the vendor key never leaves the server and usage is
 * metered per user (seconds only — audio and transcripts are never stored or logged).
 *
 * Client protocol (auth: same `Authorization: Bearer rt_...` header as REST):
 *   → text  {"type":"config", sampleRate?, language?, keywords?}   optional, before audio
 *   → binary raw PCM16 mono frames at the configured sample rate
 *   → text  {"type":"finish"}                                      no more audio
 *   ← {"type":"ready"} | {"type":"partial","text"} | {"type":"final","text"}
 *   ← {"type":"done","text","seconds"}  full transcript, then we close
 *   ← {"type":"error","message"}
 */
app.get(
  "/v1/stt/stream",
  requireAuth,
  upgradeWebSocket((c) => {
    const userId = c.var.user.id;
    let cfg: SttConfig = SttConfigSchema.parse({ type: "config" });
    let session: SttSession | null = null;
    let audioBytes = 0;
    const finals: string[] = [];

    const meter = () => {
      const seconds = Math.round(audioBytes / (cfg.sampleRate * 2));
      audioBytes = 0;
      bumpSttSeconds(userId, seconds).catch((e) =>
        console.error("stt metering failed:", (e as Error).message)
      );
    };

    return {
      onMessage(evt, ws) {
        const send = (msg: object) => ws.send(JSON.stringify(msg));

        if (typeof evt.data === "string") {
          const parsed = SttControlSchema.safeParse(safeJson(evt.data));
          if (!parsed.success) return send({ error: "invalid control message" });
          if (parsed.data.type === "config") {
            if (session) return send({ type: "error", message: "config must precede audio" });
            cfg = parsed.data;
          } else {
            // finish with no audio ever sent: nothing to flush, report an empty transcript.
            if (!session) return send({ type: "done", text: "", seconds: 0 });
            session.finish();
          }
          return;
        }

        const chunk = Buffer.from(evt.data as ArrayBuffer);
        session ??= startSession(ws, send);
        if (!session) return; // startSession already reported the error and closed
        audioBytes += chunk.length;
        session.sendAudio(chunk);
        if (audioBytes >= MAX_AUDIO_SECONDS * cfg.sampleRate * 2) session.finish();
      },
      onClose() {
        session?.destroy();
        meter();
      },
    };

    function startSession(
      ws: { close: (code?: number, reason?: string) => void },
      send: (msg: object) => void
    ): SttSession | null {
      try {
        return openSttSession(cfg, {
          onReady: () => send({ type: "ready" }),
          onPartial: (text) => send({ type: "partial", text }),
          onFinal: (text) => {
            finals.push(text);
            send({ type: "final", text });
          },
          onError: (message) => {
            send({ type: "error", message });
            ws.close(1011, "stt failed");
          },
          onDone: () => {
            send({
              type: "done",
              text: finals.join(" "),
              seconds: Math.round(audioBytes / (cfg.sampleRate * 2)),
            });
            ws.close(1000, "done");
          },
        });
      } catch (e) {
        console.error("stt session failed to start:", (e as Error).message);
        send({ type: "error", message: "stt unavailable" });
        ws.close(1011, "stt unavailable");
        return null;
      }
    }
  })
);

function safeJson(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return undefined;
  }
}

const port = Number(process.env.PORT ?? 8787);
const server = serve({ fetch: app.fetch, port });
injectWebSocket(server);
console.log(`ReplyMint backend listening on http://localhost:${port}`);
