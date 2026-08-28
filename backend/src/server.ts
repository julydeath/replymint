import { serve } from "@hono/node-server";
import { Hono } from "hono";
import { exchangeGoogleToken, requireAuth, signOut, type AuthEnv } from "./auth.js";
import { bumpUsage, todayUsage } from "./db.js";
import { generateReply } from "./llm.js";
import { buildSystem, buildUser } from "./prompts.js";
import { ReplyRequestSchema } from "./types.js";

const app = new Hono<AuthEnv>();

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

const port = Number(process.env.PORT ?? 8787);
serve({ fetch: app.fetch, port });
console.log(`ReplyMint backend listening on http://localhost:${port}`);
