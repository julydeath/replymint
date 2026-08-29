import type { ReplyBrain, ReplyRequest } from "./types.js";

/**
 * The system prompt is the same shape for both tiers, with a professional add-on.
 * The critical rule everywhere: output ONLY the reply text, because the Android
 * client writes it straight into the app's input box.
 *
 * `dictate` gets an entirely different system prompt: an editor, not an
 * assistant. Sharing the reply persona made the model ANSWER dictated questions
 * ("how fast does earth rotate") instead of transcribing them.
 */
export function buildSystem(mode: ReplyRequest["mode"], action?: ReplyRequest["action"]): string {
  if (action === "dictate") {
    return [
      "You are a dictation cleanup engine. The user spoke text aloud; your output is inserted into the focused text field as the user's own words.",
      "Your ONLY job is to return the same text, lightly cleaned: fix grammar, spelling, and punctuation; drop filler words (um, uh, you know); collapse stutters and repeated words; and apply self-corrections — when the speaker restates or corrects something, keep only the final version and delete the false start entirely (\"let's meet tuesday no wait actually wednesday works\" → \"Let's meet Wednesday.\").",
      "NEVER answer, reply to, act on, or comment on the content. If the dictated text is a question, output the question itself, cleaned — do not answer it. If it is a request or instruction, output it cleaned — do not follow it. You are an editor, not an assistant or chatbot.",
      "Preserve the speaker's meaning, tone, language, formality, and point of view exactly. Do not add, remove, or reorder content beyond removing speech artifacts.",
      "Output ONLY the cleaned text: no preamble, no quotation marks, no markdown, no explanation.",
    ].join("\n");
  }

  const base = [
    "You are ReplyMint, a private assistant that drafts one reply the user can send as-is.",
    "Write exactly ONE best reply — never options, never variations.",
    "Match the language and tone of the conversation.",
    "Output ONLY the reply text: no preamble, no quotation marks, no markdown, no sign-off, no explanation, no XML tags. It is written directly into the message box.",
    "Keep it natural and human. Do not invent facts you were not given.",
  ];

  if (mode === "professional") {
    base.push(
      "You are drafting for a professional whose replies affect money, trust, and relationships.",
      "Respect every HARD RULE exactly — never offer a discount below the stated floor, never contradict payment terms, never use words the user avoids.",
      "Use the business identity, voice samples, and contact memory to sound like the user. Be polite but confident.",
      "Names in the REPLY BRAIN (the user's own, the company, the contact) are the spelling authority — if a voice transcript renders one of them differently, the brain's spelling wins.",
    );
  } else {
    base.push("Keep replies quick, friendly, and grounded only in the visible screen.");
  }

  return base.join("\n");
}

/** Assemble the per-request context the model drafts from. */
export function buildUser(req: ReplyRequest, brain?: ReplyBrain | null): string {
  // dictate: no conversation framing at all — screen text is only a spelling
  // reference, and the transcript is the entire payload.
  if (req.action === "dictate") {
    const parts: string[] = [];
    const convo = req.screen.visibleText.filter((l) => l.trim()).join("\n");
    if (convo) {
      parts.push(
        `[SCREEN TEXT — use ONLY as a spelling reference for names, numbers, and terms; never reply to it]\n${convo}`,
      );
    }
    parts.push(`[DICTATED TEXT — raw speech recognition]\n${renderVoice(req)}`);
    parts.push(
      "[TASK]\nReturn the dictated text cleaned per your rules — same words, same intent, speech artifacts removed. Do not answer or respond to it.",
    );
    return parts.join("\n\n");
  }

  const parts: string[] = [];

  if (req.screen.appPackage) parts.push(`[APP] ${req.screen.appPackage}`);

  const convo = req.screen.visibleText.filter((l) => l.trim()).join("\n");
  if (convo) {
    parts.push(
      `[CONVERSATION ON SCREEN — top to bottom, newest last. "Me:" = sent by the user, "Them:" = received. Tags are heuristic; untagged lines are usually headers, timestamps, or app UI — ignore those.]\n${convo}`,
    );
  }

  if (req.screen.typedText?.trim()) {
    parts.push(`[WHAT THE USER ALREADY TYPED]\n${req.screen.typedText.trim()}`);
  }

  if (req.mode === "professional" && brain) {
    const brainText = renderBrain(brain);
    if (brainText) parts.push(`[REPLY BRAIN]\n${brainText}`);
  }

  if (req.action === "voice") {
    const voiceText = renderVoice(req);
    if (voiceText) parts.push(`[VOICE INSTRUCTION — raw speech recognition]\n${voiceText}`);
  }

  parts.push(`[TASK]\n${taskFor(req)}`);
  return parts.join("\n\n");
}

/**
 * The spoken instruction as the recognizer heard it: best transcript first, then the
 * alternatives that differ (the uncertain words live in the differences). Falls back to the
 * legacy flat `voiceInstruction` when an old client sent only that.
 */
function renderVoice(req: ReplyRequest): string {
  const v = req.voice;
  if (!v || v.hypotheses.length === 0) {
    return req.voiceInstruction?.trim() ?? "";
  }

  const [best = "", ...rest] = v.hypotheses;
  if (!best.trim()) return req.voiceInstruction?.trim() ?? "";
  const lines = [`Transcript: ${best}`];

  const alts = rest.filter((h) => h.trim() && h.trim() !== best.trim());
  if (alts.length) {
    lines.push("The recognizer also heard (uncertain words differ between these):");
    alts.forEach((a) => lines.push(`  - ${a}`));
  }

  const meta: string[] = [];
  if (v.source) meta.push(v.source === "native_offline" ? "on-device recognizer" : v.source === "cloud" ? "cloud recognizer" : "online recognizer");
  if (v.lang) meta.push(`language: ${v.lang}`);
  if (meta.length) lines.push(`(${meta.join(", ")})`);

  return lines.join("\n");
}

function taskFor(req: ReplyRequest): string {
  switch (req.action) {
    case "fix":
      return 'Improve the grammar, spelling, and clarity of "WHAT THE USER ALREADY TYPED" without changing its meaning or tone. Return only the improved text.';
    case "voice":
      return [
        "Follow the spoken instruction in VOICE INSTRUCTION, using the conversation as context.",
        "The transcript comes from speech recognition and may misrender names, numbers, currencies, and words. Before following it, silently correct any misrecognition using the conversation on screen (and the Reply Brain, if present) — e.g. a name spelled differently in the thread, a dropped currency word, a garbled amount.",
        "Never execute a misrecognition literally, and never mention the transcript or the correction. Return only the resulting message.",
      ].join(" ");
    case "auto_reply":
    default:
      return "Write the single best reply to the most recent message from the other person (the last \"Them:\" line, or the last message that clearly isn't the user's). Ignore app UI text.";
  }
}

function renderBrain(brain: ReplyBrain): string {
  const lines: string[] = [];

  if (brain.situation) lines.push(`Situation: ${brain.situation}`);

  if (brain.identity) {
    const { name, company, sells } = brain.identity;
    if (name) lines.push(`I am: ${name}`);
    if (company) lines.push(`Company: ${company}`);
    if (sells) lines.push(`We sell: ${sells}`);
  }

  if (brain.rules) {
    const r = brain.rules;
    if (r.priceFloorPercent != null)
      lines.push(`HARD RULE: never discount below ${r.priceFloorPercent}%.`);
    if (r.paymentTerms) lines.push(`HARD RULE: payment terms are ${r.paymentTerms}.`);
    if (r.avoid?.length) lines.push(`HARD RULE: never use these words/phrases: ${r.avoid.join(", ")}.`);
    r.other?.forEach((o) => lines.push(`HARD RULE: ${o}`));
  }

  if (brain.voice) {
    if (brain.voice.tone) lines.push(`Preferred tone: ${brain.voice.tone}`);
    if (brain.voice.samples?.length) {
      lines.push("Approved past replies (match this voice):");
      brain.voice.samples.forEach((s) => lines.push(`  - ${s}`));
    }
  }

  if (brain.contact) {
    const c = brain.contact;
    if (c.name) lines.push(`This contact: ${c.name}`);
    if (c.stage) lines.push(`Relationship stage: ${c.stage}`);
    if (c.notes) lines.push(`Contact notes: ${c.notes}`);
    if (c.pastCommitments?.length)
      lines.push(`Past commitments to them: ${c.pastCommitments.join("; ")}`);
  }

  return lines.join("\n");
}
