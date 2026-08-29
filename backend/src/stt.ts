import { deepgramSession } from "./deepgram.js";
import type { SttConfig } from "./types.js";

export type { SttConfig };

/** Callbacks a provider fires as the stream progresses. All are optional to call once closed. */
export interface SttHandlers {
  /** Upstream is connected and consuming audio. */
  onReady(): void;
  /** Low-latency interim hypothesis; may be revised. Paints the voice panel. */
  onPartial(text: string): void;
  /** A finalized segment; segments concatenate into the full transcript. */
  onFinal(text: string): void;
  /** Fatal provider error. The session is dead after this. */
  onError(message: string): void;
  /** Provider finished flushing after finish(); no more events follow. */
  onDone(): void;
}

/** A live streaming-transcription session. */
export interface SttSession {
  /** Raw PCM16 mono audio at the configured sample rate. */
  sendAudio(chunk: Buffer): void;
  /** No more audio; flush remaining finals, then onDone fires. */
  finish(): void;
  /** Abort immediately without flushing. Safe to call twice. */
  destroy(): void;
}

/**
 * Single entry point for cloud STT (VOICE_PLAN V3). Same pattern as llm.ts:
 * the provider is chosen by STT_PROVIDER:
 *   "deepgram" (default, production) | "mock" (dev/test — no key, no network).
 * The vendor key stays server-side; devices only ever talk to our proxy.
 */
export function openSttSession(cfg: SttConfig, handlers: SttHandlers): SttSession {
  const provider = (process.env.STT_PROVIDER ?? "deepgram").toLowerCase();
  return provider === "mock" ? mockSession(cfg, handlers) : deepgramSession(cfg, handlers);
}

/**
 * Fake provider for client development and proxy testing: one "segment" per second
 * of audio received, no network, no key. Mirrors the event order Deepgram produces.
 */
function mockSession(cfg: SttConfig, handlers: SttHandlers): SttSession {
  const bytesPerSecond = cfg.sampleRate * 2; // PCM16 mono
  let buffered = 0;
  let segment = 0;
  let closed = false;
  queueMicrotask(() => {
    if (!closed) handlers.onReady();
  });
  return {
    sendAudio(chunk) {
      if (closed) return;
      buffered += chunk.length;
      while (buffered >= bytesPerSecond) {
        buffered -= bytesPerSecond;
        segment++;
        handlers.onPartial(`mock partial ${segment}`);
        handlers.onFinal(`mock segment ${segment}.`);
      }
    },
    finish() {
      if (closed) return;
      closed = true;
      queueMicrotask(() => handlers.onDone());
    },
    destroy() {
      closed = true;
    },
  };
}
