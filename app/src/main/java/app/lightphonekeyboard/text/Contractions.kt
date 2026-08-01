package app.lightphonekeyboard.text

/**
 * The handful of words that are always capitalised, whatever the sentence does.
 *
 * Tracing apostrophe words is handled in the decoder itself now: a gesture traces the letters and any
 * dictionary word whose letters match is a candidate, so "don't" and "that's" and "you're" come out of
 * the dictionary with their apostrophes intact and need nothing from here.
 *
 * What the dictionary cannot supply is the capital in "I'm". It stores words lowercase, and the shift
 * state only tells you whether a *sentence* is starting — but the first person is capital in the middle
 * of a sentence too, and a keyboard that commits "i'm" mid-message is wrong every time. These four are
 * the whole of that problem in English.
 */
internal object Contractions {

    private val alwaysCapital = mapOf(
        "i'm" to "I'm",
        "i've" to "I've",
        "i'd" to "I'd",
        "i'll" to "I'll",
    )

    /**
     * [words] with the always-capital forms capitalised.
     *
     * Returns the list unchanged when there is nothing to do, so the common case allocates nothing.
     */
    fun apply(words: List<String>): List<String> {
        if (words.isEmpty()) return words
        var changed = false
        for (w in words) {
            if (alwaysCapital.containsKey(w)) {
                changed = true
                break
            }
        }
        if (!changed) return words
        return words.map { alwaysCapital[it] ?: it }
    }
}
