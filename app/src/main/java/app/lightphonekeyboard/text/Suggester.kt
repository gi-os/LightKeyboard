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

    /**
     * The word exactly as typed, offered in the leftmost slot so it can be kept — iOS's `"teh"` slot.
     *
     * Returns null when offering it would be pointless rather than helpful:
     *
     *  - nothing typed yet;
     *  - a word [UserWords] would refuse anyway (too short, digits, punctuation), because the slot's
     *    whole purpose is to add it and a slot that silently fails to is worse than no slot;
     *  - a word the keyboard already knows. Nothing to learn, nothing about to be corrected away, and
     *    quoting a correctly spelled word makes the strip look like it is second-guessing you.
     *
     * That last rule is narrower than iOS, which shows the literal whenever *any* autocorrection is
     * pending, including one real word being swapped for another. This covers the case the user
     * actually needs — names and jargon the dictionary has never heard of — without putting quotes on
     * screen for every ordinary word.
     */
    fun literalFor(prefix: String): String? {
        if (prefix.isEmpty()) return null
        if (!UserWords.isAcceptable(prefix)) return null
        if (dict.contains(prefix.lowercase()) || userWords.contains(prefix)) return null
        return prefix
    }

    /**
     * The whole strip for a word being typed: the literal first when there is one, then the ordinary
     * suggestions from [forPrefix] filling what is left.
     *
     * The literal takes a slot rather than being squeezed in beside them, so on an unknown word the
     * strip is literal + two suggestions and on a known word it is three suggestions — which is how
     * the strip stays the same width while its contents change.
     */
    fun stripFor(prefix: String, limit: Int = SLOTS): List<StripItem> {
        if (limit <= 0) return emptyList()
        val literal = literalFor(prefix)
        val out = ArrayList<StripItem>(limit)
        if (literal != null) out.add(StripItem(literal, literal = true))
        for (w in forPrefix(prefix, limit - out.size)) {
            // A completion can equal the literal in case only ("Basil" added, "basil" typed) — the
            // same word twice in one strip is a wasted slot.
            if (out.none { it.word.equals(w, ignoreCase = true) }) out.add(StripItem(w))
            if (out.size >= limit) break
        }
        return out
    }

    companion object {
        /** Slots in the strip. Three is what fits legibly at this text size on the Light Phone. */
        const val SLOTS = 3
    }
}

/**
 * One slot of the suggestion strip.
 *
 * [literal] separates "the word you typed, keep it and learn it" from an ordinary suggestion. The two
 * look different (the literal is quoted) and do different things when tapped, and the difference is
 * decided here in the pure layer rather than re-derived by comparing strings in the IME.
 */
data class StripItem(
    val word: String,
    val literal: Boolean = false,
)
