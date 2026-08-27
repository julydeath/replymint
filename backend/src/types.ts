import { z } from "zod";

export const ScreenSchema = z.object({
  appPackage: z.string().default(""),
  visibleText: z.array(z.string()).default([]),
  typedText: z.string().nullish(),
});

/**
 * The professional-mode Reply Brain, sent inline for the MVP.
 * (Phase 2 moves this server-side into Postgres + pgvector; see docs/REPLY_BRAIN.md.)
 * Every field is optional — the user fills in only what they want remembered.
 */
export const ReplyBrainSchema = z.object({
  identity: z
    .object({
      name: z.string().optional(),
      company: z.string().optional(),
      sells: z.string().optional(),
    })
    .optional(),
  rules: z
    .object({
      priceFloorPercent: z.number().optional(), // e.g. 15 => never discount below 15%
      paymentTerms: z.string().optional(), // e.g. "50% advance"
      avoid: z.array(z.string()).optional(), // words/phrases to avoid
      other: z.array(z.string()).optional(), // free-form hard rules
    })
    .optional(),
  voice: z
    .object({
      tone: z.string().optional(), // e.g. "polite but confident, concise"
      samples: z.array(z.string()).optional(), // approved past replies
    })
    .optional(),
  contact: z
    .object({
      name: z.string().optional(),
      stage: z.string().optional(), // new lead / active client / long-term / cold
      notes: z.string().optional(),
      pastCommitments: z.array(z.string()).optional(),
    })
    .optional(),
  situation: z.string().optional(), // sales / support / negotiation / hiring / ...
});

export const ReplyRequestSchema = z.object({
  mode: z.enum(["personal", "professional"]),
  action: z.enum(["auto_reply", "voice", "fix"]),
  screen: ScreenSchema,
  voiceInstruction: z.string().nullish(),
  brain: ReplyBrainSchema.nullish(), // used only in professional mode
});

export type ScreenPayload = z.infer<typeof ScreenSchema>;
export type ReplyBrain = z.infer<typeof ReplyBrainSchema>;
export type ReplyRequest = z.infer<typeof ReplyRequestSchema>;
