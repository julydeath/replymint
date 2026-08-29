import WebSocket from "ws";
import type { SttConfig, SttHandlers, SttSession } from "./stt.js";

/**
 * Deepgram streaming STT over WebSocket (VOICE_PLAN V3 provider choice: lowest
 * latency, keyword boosting via nova-3 keyterms, ~$0.005/min).
 *
 * Privacy: transcripts are never logged here — only error class/message, same
 * rule as the LLM path. Ship gate: a zero-retention agreement with Deepgram
 * must be in place before launch or PRIVACY.md is false.
 */
const DEEPGRAM_URL = "wss://api.deepgram.com/v1/listen";

/** Deepgram drops the connection after ~10s without audio; ping to keep it alive. */
const KEEPALIVE_MS = 5_000;

export function deepgramSession(cfg: SttConfig, handlers: SttHandlers): SttSession {
  const key = process.env.DEEPGRAM_API_KEY;
  if (!key) throw new Error("DEEPGRAM_API_KEY is not set");

  const params = new URLSearchParams({
    model: process.env.DEEPGRAM_MODEL ?? "nova-3",
    encoding: "linear16", // raw PCM16, matching AudioPipeline's output
    sample_rate: String(cfg.sampleRate),
    channels: "1",
    interim_results: "true",
    smart_format: "true", // punctuation, numbers, currency — what native STT gets wrong
    endpointing: "300",
  });
  if (cfg.language) params.set("language", cfg.language);
  // Screen-context boosting: the V2 trick applied at the STT layer. Names and terms
  // read from the conversation bias recognition toward the words actually in play.
  for (const term of cfg.keywords) params.append("keyterm", term);

  const ws = new WebSocket(`${DEEPGRAM_URL}?${params}`, {
    headers: { Authorization: `Token ${key}` },
  });

  // Audio can arrive from the device before the upstream socket opens; buffer it.
  let pending: Buffer[] = [];
  let finishRequested = false;
  let done = false;
  let keepalive: NodeJS.Timeout | undefined;

  const settle = (fire?: () => void) => {
    if (done) return;
    done = true;
    clearInterval(keepalive);
    fire?.();
  };

  ws.on("open", () => {
    for (const chunk of pending) ws.send(chunk);
    pending = [];
    if (finishRequested) ws.send(JSON.stringify({ type: "CloseStream" }));
    keepalive = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "KeepAlive" }));
    }, KEEPALIVE_MS);
    handlers.onReady();
  });

  ws.on("message", (data) => {
    let msg: { type?: string; is_final?: boolean; channel?: { alternatives?: { transcript?: string }[] } };
    try {
      msg = JSON.parse(data.toString());
    } catch {
      return;
    }
    if (msg.type !== "Results") return; // Metadata / UtteranceEnd — nothing to forward
    const text = msg.channel?.alternatives?.[0]?.transcript ?? "";
    if (!text) return;
    if (msg.is_final) handlers.onFinal(text);
    else handlers.onPartial(text);
  });

  ws.on("error", (err) => {
    console.error("deepgram stream error:", err.message);
    settle(() => handlers.onError("stt provider error"));
  });

  // Deepgram closes the socket itself after CloseStream has been flushed.
  ws.on("close", () => settle(() => handlers.onDone()));

  return {
    sendAudio(chunk) {
      if (done || finishRequested) return;
      if (ws.readyState === WebSocket.OPEN) ws.send(chunk);
      else if (ws.readyState === WebSocket.CONNECTING) pending.push(chunk);
    },
    finish() {
      if (done || finishRequested) return;
      finishRequested = true;
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: "CloseStream" }));
      // If still CONNECTING, the open handler sends CloseStream after the flush.
    },
    destroy() {
      settle();
      ws.terminate();
    },
  };
}
