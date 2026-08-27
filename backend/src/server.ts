import { serve } from "@hono/node-server";
import { Hono } from "hono";
import { generateReply } from "./llm.js";
import { buildSystem, buildUser } from "./prompts.js";
import { ReplyRequestSchema } from "./types.js";

const app = new Hono();

app.get("/health", (c) => c.json({ ok: true }));

/**
 * POST /v1/reply — the whole product in one endpoint.
 * Body: { mode, action, screen, voiceInstruction?, brain? } -> { draft }
 *
 * Privacy: personal mode is stateless (nothing stored); professional mode uses
 * the Reply Brain but we never persist raw screen text here. We also never log
 * prompt contents — only error class/message.
 */
app.post("/v1/reply", async (c) => {
  // Auth is a stub for the MVP: require a bearer token, verify it for real later.
  const auth = c.req.header("authorization") ?? "";
  if (!auth.startsWith("Bearer ")) {
    return c.json({ error: "unauthorized" }, 401);
  }

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

  // Personal mode never uses a brain, even if one is sent.
  const brain = req.mode === "professional" ? req.brain : undefined;

  try {
    const draft = await generateReply({
      mode: req.mode,
      system: buildSystem(req.mode),
      user: buildUser(req, brain),
    });
    if (!draft) return c.json({ error: "empty draft" }, 502);
    return c.json({ draft });
  } catch (e) {
    console.error("reply generation failed:", (e as Error).message);
    return c.json({ error: "generation failed" }, 502);
  }
});

const port = Number(process.env.PORT ?? 8787);
serve({ fetch: app.fetch, port });
console.log(`ReplyMint backend listening on http://localhost:${port}`);
