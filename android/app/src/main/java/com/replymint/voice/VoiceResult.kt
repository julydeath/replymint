package com.replymint.voice

/** Which engine produced a transcript. Serialized to the backend as the `source` field in V2. */
enum class VoiceSource(val wire: String) {
    NATIVE_ON_DEVICE("native_offline"),
    NATIVE_NETWORK("native_online"),
    CLOUD("cloud"),
}

/**
 * Everything a recognition run tells us — not just the winning string.
 *
 * The alternatives and scores are the raw material for V2's screen-context correction: when the
 * engine returns "chal Sanjay" and the thread on screen says *Sanjay*, the alternatives are how
 * the model knows which words were uncertain. Both are best-effort by design — the V0 spike
 * measured the on-device engine returning confidences of exactly `0.0`, and no alternatives at
 * all in some configurations — so nothing downstream may require them.
 */
data class VoiceResult(
    val text: String,
    /** n-best, best first. Always contains at least [text] when anything was heard. */
    val hypotheses: List<String>,
    /** Parallel to [hypotheses] where the engine supplies scores; often empty or all zero. */
    val confidences: List<Float>,
    val source: VoiceSource,
    /** BCP-47, e.g. "en-IN". */
    val lang: String,
)
