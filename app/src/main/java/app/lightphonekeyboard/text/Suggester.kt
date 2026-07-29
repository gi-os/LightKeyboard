package app.lightphonekeyboard.text

/**
 * What goes in the three slots of the suggestion strip.
 *
 * Three different things can want that space, and which one applies depends only on where the cursor
 * is, so they are resolved in one place rather than scattered through the IME:
 *
 *  1. Mid-word, the completions of what's been typed so far — "keyb" offers "keyboard".
 *  2. Mid-word on something that isn't going anywhere, the corrections for it, so a typo can be fixed
 *     by tapping rather than by finishing the word and trusting autocorrect.
 *  3. After a swipe, the other words that trace could have been. Those come straight from the decoder,
 *     so they're passed in rather than computed here.
 *
 * The user's own words take part in all of it, which is the point of adding them — see [UserWords].
 *
 * Pure logic, no Android types.
 */
class Suggester(
    private val dict: Dictionary,
    private val corrector: Corrector,
) {
    @Volatile
    var userWords: UserWords = UserWords.EMPTY

    /**
     * Suggestions for a word being typed. [prefix] is the run of letters before the cursor; empty
     * gives nothing, since there is nothing yet to predict from and a strip full of the commonest words
     * in English is just noise.
     *
     * Completions come first — while you are still typing, the word you are reaching for is far more
     * likely to be an extension of what you have than a repair of it. Corrections fill the remaining
     * slots, and only when the prefix is not itself a word: offering to "fix" something spelled right
     * is how a suggestion strip earns distrust.
     */
    fun forPrefix(prefix: String, limit: Int = SLOTS): List<String> {
        val p = prefix.lowercase()
        if (p.isEmpty() || limit <= 0) return emptyList()
        if (p.any { it !in 'a'..'z' && it != '\'' }) return emptyList()

        val out = ArrayList<String>(limit)
        // The user's words first among completions, not merged by score: someone who went to the
        // trouble of adding "Basil" means it, and it should not have to outrank "base" on corpus
        // frequency to be visible.
        userWords.dictionary?.let { ud ->
            for (i in ud.completions(p, limit)) {
                val w = userWords.displayOf(ud.word(i))
                if (out.none { it.equals(w, ignoreCase = true) }) out.add(w)
                if (out.size >= limit) return out
            }
        }
        for (i in dict.completions(p, limit)) {
            val w = dict.word(i)
            if (out.none { it.equals(w, ignoreCase = true) }) out.add(w)
            if (out.size >= limit) return out
        }
        if (!dict.contains(p) && !userWords.contains(p)) {
            for (w in corrector.suggest(prefix, limit)) {
                if (out.none { it.equals(w, ignoreCase = true) }) out.add(w)
                if (out.size >= limit) break
            }
        }
        return out
    }

    companion object {
        /** Slots in the strip. Three is what fits legibly at this text size on the Light Phone. */
        const val SLOTS = 3
    }
}
