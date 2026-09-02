import Anthropic from "@anthropic-ai/sdk";
import { cleanDraft } from "./clean.js";

/**
 * Lazily construct the client so importing this module without ANTHROPIC_API_KEY
 * (e.g. when LLM_PROVIDER=ollama for dev/test) does not throw. The SDK constructor
 * throws when the key is missing, so we only build it when Anthropic is actually used.
 */
let client: Anthropic | null = null;
function getClient(): Anthropic {
  // Fail inside the desktop client's 45s window (reply.rs): 20s per attempt,
  // one retry. The SDK default (10 min, 2 retries) would leave the client
  // timing out with no idea why.
  return (client ??= new Anthropic({ timeout: 20_000, maxRetries: 1 }));
}

/**
 * Model tiering is a deliberate product decision (see docs/ROADMAP.md pricing):
 * Personal gets a cheap, fast model; Professional gets the strongest model.
 * This is the paid-tier lever, not an arbitrary downgrade.
 */
export const MODEL = {
  personal: "claude-haiku-4-5",
  professional: "claude-opus-5",
} as const;

/**
 * One short reply. We disable thinking on the professional (Opus 5) call:
 * this is a tool-less, short-output drafting task, so thinking would only add
 * latency, cost, and truncation risk (max_tokens caps thinking + text together).
 * The "output only the reply text" system rule plus the shared strip keep it clean.
 */
export async function anthropicReply(opts: {
  mode: "personal" | "professional";
  system: string;
  user: string;
}): Promise<string> {
  const isPro = opts.mode === "professional";

  const message = await getClient().messages.create({
    model: isPro ? MODEL.professional : MODEL.personal,
    // A polished 5-minute dictation (~750 words) is ~1k tokens: 1024 truncated it.
    max_tokens: 4096,
    system: opts.system,
    messages: [{ role: "user", content: opts.user }],
    // Opus 5 thinks by default; turn it off for this short reply task.
    // Haiku 4.5 defaults to no thinking, so nothing to set there.
    ...(isPro ? { thinking: { type: "disabled" as const } } : {}),
  });

  const text = message.content
    .filter((b): b is Anthropic.TextBlock => b.type === "text")
    .map((b) => b.text)
    .join("");

  return cleanDraft(text);
}
