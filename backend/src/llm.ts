import { anthropicReply } from "./anthropic.js";
import { ollamaReply } from "./ollama.js";

export interface GenerateOpts {
  mode: "personal" | "professional";
  system: string;
  user: string;
}

/**
 * Single entry point for drafting. The provider is chosen by LLM_PROVIDER:
 *   "anthropic" (default, production) | "ollama" (dev/test via Ollama Cloud).
 * Swapping providers is an env change only — callers (server.ts) don't change.
 */
export function generateReply(opts: GenerateOpts): Promise<string> {
  const provider = (process.env.LLM_PROVIDER ?? "anthropic").toLowerCase();
  return provider === "ollama" ? ollamaReply(opts) : anthropicReply(opts);
}
