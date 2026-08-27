/** Defensive: strip any stray internal tags or wrapping quotes a model might add. */
export function cleanDraft(raw: string): string {
  return raw
    .replace(/<thinking>[\s\S]*?<\/thinking>/gi, "")
    .trim()
    .replace(/^["'`]+|["'`]+$/g, "")
    .trim();
}
