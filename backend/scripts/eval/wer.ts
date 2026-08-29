/**
 * Word error rate for the V3 quality gate (VOICE_PLAN Part 4).
 *
 * Normalization is deliberately minimal: lowercase, strip punctuation (keeping
 * intra-word apostrophes: "don't"), strip digit-group commas ("42,000" → "42000"),
 * collapse whitespace. No spelled-number canonicalization — the fixture convention
 * is that references write numbers as digits, which matches Deepgram smart_format.
 */

export function normalize(s: string): string {
  return s
    .toLowerCase()
    .replace(/(\d),(?=\d)/g, "$1") // digit-group commas
    .replace(/[^\p{L}\p{N}\s']/gu, " ") // punctuation → space (keep letters/digits/apostrophes)
    .replace(/(^|\s)'|'(?=\s|$)/g, " ") // quote-apostrophes not inside a word
    .replace(/\s+/g, " ")
    .trim();
}

export interface WerResult {
  /** (S + I + D) / refWords, or 0 when both sides are empty. */
  wer: number;
  substitutions: number;
  insertions: number;
  deletions: number;
  refWords: number;
  /** S + I + D — poolable across clips (pooled WER = Σedits / ΣrefWords). */
  edits: number;
}

/** Word-level Levenshtein between normalized ref and hyp. */
export function wer(ref: string, hyp: string): WerResult {
  const r = normalize(ref).split(" ").filter(Boolean);
  const h = normalize(hyp).split(" ").filter(Boolean);

  // dp[i][j] = min edits to turn r[0..i) into h[0..j); track op counts for the S/I/D breakdown.
  const rows = r.length + 1;
  const cols = h.length + 1;
  const cost = Array.from({ length: rows }, () => new Array<number>(cols).fill(0));
  for (let i = 1; i < rows; i++) cost[i][0] = i;
  for (let j = 1; j < cols; j++) cost[0][j] = j;
  for (let i = 1; i < rows; i++) {
    for (let j = 1; j < cols; j++) {
      const sub = cost[i - 1][j - 1] + (r[i - 1] === h[j - 1] ? 0 : 1);
      cost[i][j] = Math.min(sub, cost[i - 1][j] + 1, cost[i][j - 1] + 1);
    }
  }

  // Backtrace for the breakdown.
  let substitutions = 0;
  let insertions = 0;
  let deletions = 0;
  let i = r.length;
  let j = h.length;
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && cost[i][j] === cost[i - 1][j - 1] && r[i - 1] === h[j - 1]) {
      i--;
      j--;
    } else if (i > 0 && j > 0 && cost[i][j] === cost[i - 1][j - 1] + 1) {
      substitutions++;
      i--;
      j--;
    } else if (i > 0 && cost[i][j] === cost[i - 1][j] + 1) {
      deletions++;
      i--;
    } else {
      insertions++;
      j--;
    }
  }

  const edits = substitutions + insertions + deletions;
  return {
    wer: r.length === 0 ? (h.length === 0 ? 0 : 1) : edits / r.length,
    substitutions,
    insertions,
    deletions,
    refWords: r.length,
    edits,
  };
}
