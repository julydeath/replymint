# Eval fixtures

Voice clips are personal data — everything here except this README is gitignored.

Each clip is a pair (plus one optional file):

- `<name>.wav` — PCM16 mono 16kHz (record with `../record.sh`; convert anything else with
  `ffmpeg -i in.m4a -ac 1 -ar 16000 -c:a pcm_s16le <name>.wav`)
- `<name>.txt` — exact ground-truth transcript. **Write numbers as digits** (`42000`, not
  "forty two thousand") — Deepgram smart_format emits digits and the WER normalizer does
  not canonicalize spelled numbers.
- `<name>.keywords.txt` — optional, one keyword per line (max 50): the proper nouns a real
  session would send from screen context. This is how the "context-corrected" column of the
  VOICE_PLAN eval is measured.

## Target clip mix (VOICE_PLAN Part 4 — 20–50 starter clips, 150–200 eventually)

- Indian-English accented everyday sentences
- Hinglish code-switching
- Proper nouns: names of people and businesses (pair with `.keywords.txt`)
- Numbers, prices, dates ("the invoice for 42000 rupees is due on Friday")
- Background noise (street, cafe, TV)
- Long utterances with pauses (tests session chaining / endpointing)

Canonical clip (VOICE_PLAN): *"Tell Sanjay the invoice for forty two thousand rupees is due
on Friday"* — ref: `tell sanjay the invoice for 42000 rupees is due on friday`,
keywords: `Sanjay`.

## Running

```
npm run eval              # paced, real latencies — the gate numbers
npm run eval -- --fast    # firehose, protocol check vs STT_PROVIDER=mock
npm run eval -- --hyp-dir <dir>   # score Android-native .hyp.txt files (see NativeEval)
```

Gate (VOICE_PLAN V3): pooled cloud WER < pooled native WER, and p90 finish→done < 1500ms.
