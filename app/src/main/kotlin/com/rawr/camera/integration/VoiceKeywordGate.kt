package com.rawr.camera.integration

/**
 * Decides when the recognizer's running hypothesis means "take the picture" (Settings > Photo > Capture > Voice Shutter).
 *
 * Measured on pronunciation recordings of the two words, by five speakers, against 18 look-alike words (chess, cheers,
 * texture, picture, ...) with the small English model decoding its full vocabulary: a hypothesis that is EXACTLY a keyword
 * never came from a look-alike word. Restricting the recognizer to the keywords instead made 3 to 7 of 10 held-out look-alikes
 * fire. The remaining false trigger is a longer sentence that merely begins with the keyword: its hypothesis is exactly the
 * keyword until the next word arrives. Requiring it to stay unchanged for [stableMs] removes that (0 of 3 sentences with a
 * pause of 0.10 s or 0.35 s and more after the word) at 0.25 s of latency; a final result that agrees fires at once, which
 * catches a word the partial hypothesis never held steady.
 *
 * After a trigger everything is ignored for [cooldownMs], so one "cheese" is one photo.
 */
internal class VoiceKeywordGate(
    private val keywords: Set<String> = DEFAULT_KEYWORDS,
    private val stableMs: Long = DEFAULT_STABLE_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private var current = ""
    private var since = 0L
    private var cooldownUntil = Long.MIN_VALUE
    private var fired = false

    /** The keyword to act on, or null. [text] is the recognizer's whole hypothesis; [isFinal] marks a final result. */
    fun onHypothesis(text: String, isFinal: Boolean, nowMs: Long): String? {
        if (nowMs < cooldownUntil) {
            current = ""
            fired = false
            return null
        }
        val hypothesis = text.trim().lowercase()
        if (hypothesis != current) {
            current = hypothesis
            since = nowMs
            fired = false
        }
        if (hypothesis !in keywords || fired) return null
        if (!isFinal && nowMs - since < stableMs) return null
        fired = true
        cooldownUntil = nowMs + cooldownMs
        return hypothesis
    }

    /** Forget the hypothesis in progress (the recognizer was reset). The cooldown stays. */
    fun reset() {
        current = ""
        fired = false
    }

    companion object {
        // "captured": how the small English model hears "capture" from a non-native speaker (a Portuguese speaker on the
        // 17 Ultra: "captured" three times out of three); it is the same word, so it cannot come from a look-alike.
        val DEFAULT_KEYWORDS = setOf("cheese", "capture", "captured")
        const val DEFAULT_STABLE_MS = 300L
        const val DEFAULT_COOLDOWN_MS = 2500L
    }
}
