import type { ReplyBrain, ReplyRequest } from "./types.js";

/**
 * The system prompt is the same shape for both tiers, with a professional add-on.
 * The critical rule everywhere: output ONLY the reply text, because the Android
 * client writes it straight into the app's input box.
 */
export function buildSystem(mode: ReplyRequest["mode"]): string {
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
    );
  } else {
    base.push("Keep replies quick, friendly, and grounded only in the visible screen.");
  }

  return base.join("\n");
}

/** Assemble the per-request context the model drafts from. */
export function buildUser(req: ReplyRequest, brain?: ReplyBrain | null): string {
  const parts: string[] = [];

  if (req.screen.appPackage) parts.push(`[APP] ${req.screen.appPackage}`);

  const convo = req.screen.visibleText.filter((l) => l.trim()).join("\n");
  if (convo) parts.push(`[CONVERSATION ON SCREEN]\n${convo}`);

  if (req.screen.typedText?.trim()) {
    parts.push(`[WHAT THE USER ALREADY TYPED]\n${req.screen.typedText.trim()}`);
  }

  if (req.mode === "professional" && brain) {
    const brainText = renderBrain(brain);
    if (brainText) parts.push(`[REPLY BRAIN]\n${brainText}`);
  }

  parts.push(`[TASK]\n${taskFor(req)}`);
  return parts.join("\n\n");
}

function taskFor(req: ReplyRequest): string {
  switch (req.action) {
    case "fix":
      return 'Improve the grammar, spelling, and clarity of "WHAT THE USER ALREADY TYPED" without changing its meaning or tone. Return only the improved text.';
    case "voice":
      return `Follow this spoken instruction from the user, using the conversation as context: "${req.voiceInstruction ?? ""}". Return only the resulting message.`;
    case "auto_reply":
    default:
      return "Write the single best reply to the most recent incoming message in the conversation.";
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
