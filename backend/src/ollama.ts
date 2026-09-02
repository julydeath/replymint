import { cleanDraft } from "./clean.js";

/**
 * Ollama Cloud provider — for dev/test when there's no Anthropic credit.
 *
 * Same wire format as local Ollama, pointed at https://ollama.com with a Bearer API
 * key. No SDK: one fetch to /api/chat (Node 22 has global fetch). Stateless, like the
 * Anthropic path — nothing is persisted or logged here.
 */
const BASE_URL = process.env.OLLAMA_BASE_URL ?? "https://ollama.com";
const DEFAULT_MODEL = "gpt-oss:120b";

/** Model per tier, mirroring the Anthropic personal/professional split. */
function modelFor(mode: "personal" | "professional"): string {
  return mode === "professional"
    ? process.env.OLLAMA_MODEL_PROFESSIONAL ?? DEFAULT_MODEL
    : process.env.OLLAMA_MODEL_PERSONAL ?? DEFAULT_MODEL;
}

interface OllamaChatResponse {
  message?: { role?: string; content?: string };
}

export async function ollamaReply(opts: {
  mode: "personal" | "professional";
  system: string;
  user: string;
}): Promise<string> {
  const apiKey = process.env.OLLAMA_API_KEY;
  if (!apiKey) throw new Error("OLLAMA_API_KEY is not set");

  const res = await fetch(`${BASE_URL}/api/chat`, {
    method: "POST",
    signal: AbortSignal.timeout(40_000), // inside the desktop client's 45s window
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model: modelFor(opts.mode),
      stream: false,
      messages: [
        { role: "system", content: opts.system },
        { role: "user", content: opts.user },
      ],
      // Short, natural drafts; no need for long generations.
      options: { temperature: 0.7 },
    }),
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`ollama ${res.status}: ${body.slice(0, 300)}`);
  }

  const json = (await res.json()) as OllamaChatResponse;
  return cleanDraft(json.message?.content ?? "");
}
