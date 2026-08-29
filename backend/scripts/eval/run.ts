/**
 * V3 quality-gate eval harness (VOICE_PLAN Part 4). Streams fixture clips through
 * the real /v1/stt/stream WebSocket and scores WER + latency:
 *
 *   npm run eval                     # paced (real-time) — the gate numbers
 *   npm run eval -- --fast           # firehose — protocol/plumbing runs vs STT_PROVIDER=mock
 *   npm run eval -- --hyp-dir <dir>  # no streaming: score <dir>/<name>.hyp.txt vs refs
 *                                    # (how the Android native baseline is scored)
 *
 * Fixtures: scripts/eval/fixtures/<name>.wav (PCM16 mono 16kHz; see record.sh) +
 * <name>.txt ground truth + optional <name>.keywords.txt (one keyword per line).
 * Clips are personal data — the fixtures dir is gitignored.
 *
 * Latency is measured client-side because the gate ("<1.5s final transcript") is a
 * user-experienced number: the gate metric is finish-sent → done-received, p90 < 1500ms.
 * Like stt-smoke, it creates a throwaway pro user + token in the DB and deletes it after.
 */
import { createHash, randomBytes } from "node:crypto";
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import { performance } from "node:perf_hooks";
import postgres from "postgres";
import WebSocket from "ws";
import { wer, type WerResult } from "./wer.js";

const BASE = process.env.SMOKE_BASE_URL ?? "ws://localhost:8787";
const FIXTURES = join(import.meta.dirname, "fixtures");
const OUT_DIR = join(import.meta.dirname, "out");
const FRAME_MS = 250;

const args = process.argv.slice(2);
const fast = args.includes("--fast");
const hypDir = args.includes("--hyp-dir") ? args[args.indexOf("--hyp-dir") + 1] : undefined;
if (args.includes("--hyp-dir") && !hypDir) throw new Error("--hyp-dir needs a directory");

interface Clip {
  name: string;
  ref: string;
  pcm?: Buffer; // absent in --hyp-dir mode
  keywords: string[];
}

interface ClipResult {
  name: string;
  ref: string;
  hyp: string;
  score: WerResult;
  firstPartialMs?: number;
  lastAudioToDoneMs?: number;
  finishToDoneMs?: number;
}

/** Minimal RIFF walker: returns PCM data iff the file is PCM16 mono 16kHz. */
function readWav(path: string): Buffer {
  const convert = `ffmpeg -i <input> -ac 1 -ar 16000 -c:a pcm_s16le ${basename(path)}`;
  const buf = readFileSync(path);
  if (buf.length < 12 || buf.toString("ascii", 0, 4) !== "RIFF" || buf.toString("ascii", 8, 12) !== "WAVE") {
    throw new Error(`${basename(path)}: not a RIFF/WAVE file — convert with: ${convert}`);
  }
  let fmt: { format: number; channels: number; sampleRate: number; bits: number } | undefined;
  let off = 12;
  while (off + 8 <= buf.length) {
    const id = buf.toString("ascii", off, off + 4);
    const size = buf.readUInt32LE(off + 4);
    const body = off + 8;
    if (id === "fmt ") {
      fmt = {
        format: buf.readUInt16LE(body),
        channels: buf.readUInt16LE(body + 2),
        sampleRate: buf.readUInt32LE(body + 4),
        bits: buf.readUInt16LE(body + 14),
      };
    } else if (id === "data") {
      if (!fmt) throw new Error(`${basename(path)}: data chunk before fmt chunk`);
      if (fmt.format !== 1 || fmt.channels !== 1 || fmt.sampleRate !== 16000 || fmt.bits !== 16) {
        throw new Error(
          `${basename(path)}: need PCM16 mono 16kHz, got format=${fmt.format} ch=${fmt.channels} ` +
            `rate=${fmt.sampleRate} bits=${fmt.bits} — convert with: ${convert}`
        );
      }
      return buf.subarray(body, body + Math.min(size, buf.length - body));
    }
    off = body + size + (size % 2); // chunks are word-aligned
  }
  throw new Error(`${basename(path)}: no data chunk found — convert with: ${convert}`);
}

function loadClips(): Clip[] {
  let entries: string[];
  try {
    entries = readdirSync(FIXTURES);
  } catch {
    throw new Error(`no fixtures dir at ${FIXTURES} — record clips with scripts/eval/record.sh`);
  }
  const clips: Clip[] = [];
  for (const f of entries.filter((f) => f.endsWith(".wav") || f.endsWith(".raw")).sort()) {
    const name = f.replace(/\.(wav|raw)$/, "");
    let ref: string;
    try {
      ref = readFileSync(join(FIXTURES, `${name}.txt`), "utf8").trim();
    } catch {
      console.error(`skipping ${f}: no ${name}.txt ground truth`);
      continue;
    }
    let keywords: string[] = [];
    try {
      keywords = readFileSync(join(FIXTURES, `${name}.keywords.txt`), "utf8")
        .split("\n")
        .map((k) => k.trim())
        .filter(Boolean)
        .slice(0, 50);
    } catch {
      /* optional */
    }
    const pcm = hypDir
      ? undefined
      : f.endsWith(".wav")
        ? readWav(join(FIXTURES, f))
        : readFileSync(join(FIXTURES, f));
    clips.push({ name, ref, pcm, keywords });
  }
  if (clips.length === 0) throw new Error(`no <name>.wav + <name>.txt pairs in ${FIXTURES}`);
  return clips;
}

/** Stream one clip through the proxy; resolves with hyp + latency marks. */
function streamClip(clip: Clip, token: string): Promise<ClipResult> {
  return new Promise((resolve, reject) => {
    const pcm = clip.pcm!;
    const frameBytes = (16000 * 2 * FRAME_MS) / 1000;
    const ws = new WebSocket(`${BASE}/v1/stt/stream`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    let tFirstPartial: number | undefined;
    let tLastAudioSent = 0;
    let tFinishSent = 0;

    ws.on("open", async () => {
      ws.send(
        JSON.stringify({ type: "config", sampleRate: 16000, language: "en", keywords: clip.keywords })
      );
      const t0 = performance.now();
      for (let i = 0; i * frameBytes < pcm.length; i++) {
        if (!fast) {
          // Drift-corrected pacing: sleep until this frame's real-time slot.
          const due = t0 + i * FRAME_MS;
          const wait = due - performance.now();
          if (wait > 0) await new Promise((r) => setTimeout(r, wait));
        }
        if (ws.readyState !== WebSocket.OPEN) return; // server closed early (error path)
        ws.send(pcm.subarray(i * frameBytes, (i + 1) * frameBytes));
      }
      tLastAudioSent = performance.now();
      ws.send(JSON.stringify({ type: "finish" }));
      tFinishSent = performance.now();
    });

    ws.on("message", (d) => {
      const msg = JSON.parse(d.toString());
      if (msg.type === "partial" && tFirstPartial === undefined) tFirstPartial = performance.now();
      if (msg.type === "done") {
        const tDone = performance.now();
        resolve({
          name: clip.name,
          ref: clip.ref,
          hyp: msg.text ?? "",
          score: wer(clip.ref, msg.text ?? ""),
          firstPartialMs: tFirstPartial,
          lastAudioToDoneMs: tLastAudioSent ? tDone - tLastAudioSent : undefined,
          finishToDoneMs: tFinishSent ? tDone - tFinishSent : undefined,
        });
      }
      if (msg.type === "error") reject(new Error(`${clip.name}: server error: ${msg.message}`));
    });
    ws.on("error", (e) => reject(new Error(`${clip.name}: ${e.message}`)));
    ws.on("close", (code) => {
      // done resolves first on the happy path; any other close is a failure.
      reject(new Error(`${clip.name}: closed ${code} before done`));
    });
  });
}

function scoreHypDir(clips: Clip[], dir: string): ClipResult[] {
  const results: ClipResult[] = [];
  for (const clip of clips) {
    let hyp: string;
    try {
      hyp = readFileSync(join(dir, `${clip.name}.hyp.txt`), "utf8").trim();
    } catch {
      console.error(`skipping ${clip.name}: no ${clip.name}.hyp.txt in ${dir}`);
      continue;
    }
    results.push({ name: clip.name, ref: clip.ref, hyp, score: wer(clip.ref, hyp) });
  }
  return results;
}

function p90(values: number[]): number | undefined {
  if (values.length === 0) return undefined;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(0.9 * sorted.length) - 1)];
}

function report(results: ClipResult[], label: string) {
  const pad = (s: string, n: number) => s.padEnd(n);
  const num = (v: number | undefined, n: number) =>
    (v === undefined ? "—" : String(Math.round(v))).padStart(n);
  console.log(`\n${label}`);
  console.log(
    pad("clip", 28) + "refW".padStart(6) + "WER%".padStart(8) + "S".padStart(5) + "I".padStart(5) +
      "D".padStart(5) + "1stPart".padStart(9) + "fin→done".padStart(10)
  );
  for (const r of results) {
    console.log(
      pad(r.name, 28) +
        String(r.score.refWords).padStart(6) +
        (r.score.wer * 100).toFixed(1).padStart(8) +
        String(r.score.substitutions).padStart(5) +
        String(r.score.insertions).padStart(5) +
        String(r.score.deletions).padStart(5) +
        num(r.firstPartialMs, 9) +
        num(r.finishToDoneMs, 10)
    );
  }
  // Pooled WER (Σ edits / Σ ref words), not mean-of-WERs — short clips shouldn't dominate.
  const edits = results.reduce((a, r) => a + r.score.edits, 0);
  const refWords = results.reduce((a, r) => a + r.score.refWords, 0);
  const lat = results.map((r) => r.finishToDoneMs).filter((v): v is number => v !== undefined);
  const pooled = refWords ? (edits / refWords) * 100 : 0;
  const p90ms = p90(lat);
  console.log(
    pad(`TOTAL (${results.length} clips, pooled)`, 28) +
      String(refWords).padStart(6) +
      pooled.toFixed(1).padStart(8) +
      "".padStart(15) +
      "".padStart(9) +
      num(p90ms, 10) + (p90ms !== undefined ? " (p90)" : "")
  );
  if (p90ms !== undefined) {
    console.log(`gate: pooled WER ${pooled.toFixed(1)}% · p90 finish→done ${Math.round(p90ms)}ms ` +
      `(${p90ms < 1500 ? "PASS" : "FAIL"} <1500ms)`);
  }
  return { pooledWerPct: pooled, p90FinishToDoneMs: p90ms, clips: results.length, refWords };
}

const clips = loadClips();

if (hypDir) {
  const results = scoreHypDir(clips, hypDir);
  const aggregate = report(results, `hyp-dir scoring: ${hypDir}`);
  mkdirSync(OUT_DIR, { recursive: true });
  const out = join(OUT_DIR, `${new Date().toISOString().replace(/[:.]/g, "-")}.json`);
  writeFileSync(out, JSON.stringify({ mode: "hyp-dir", hypDir, aggregate, results }, null, 2));
  console.log(`\nwrote ${out}`);
} else {
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error("DATABASE_URL is not set (run with --env-file=.env)");
  const sql = postgres(url, { prepare: false, max: 1 });
  const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");
  const [user] = await sql<{ id: string }[]>`
    insert into users (google_sub, email, name, plan)
    values ('eval-harness', 'stt-eval@test.invalid', 'STT Eval', 'pro')
    on conflict (google_sub) do update set plan = 'pro'
    returning id
  `;
  if (!user) throw new Error("failed to create eval user");
  const token = "rt_" + randomBytes(32).toString("base64url");
  await sql`insert into tokens (token_hash, user_id) values (${sha256(token)}, ${user.id})`;

  try {
    const results: ClipResult[] = [];
    for (const clip of clips) {
      // Sequential on purpose: parallel sessions would contend for upstream capacity
      // and pollute the latency numbers.
      try {
        const r = await streamClip(clip, token);
        results.push(r);
        console.log(`${clip.name}: WER ${(r.score.wer * 100).toFixed(1)}%  "${r.hyp}"`);
      } catch (e) {
        console.error((e as Error).message);
      }
    }
    if (results.length === 0) throw new Error("no clips produced results");
    const aggregate = report(results, `streamed ${fast ? "(--fast, latencies not meaningful)" : "(paced)"} vs ${BASE}`);
    mkdirSync(OUT_DIR, { recursive: true });
    const out = join(OUT_DIR, `${new Date().toISOString().replace(/[:.]/g, "-")}.json`);
    writeFileSync(out, JSON.stringify({ mode: fast ? "fast" : "paced", base: BASE, aggregate, results }, null, 2));
    console.log(`\nwrote ${out}`);
  } finally {
    await sql`delete from users where id = ${user.id}`;
    await sql.end();
  }
}
