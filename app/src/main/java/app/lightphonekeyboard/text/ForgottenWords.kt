package app.lightphonekeyboard.text

/**
 * The words you told the keyboard to stop offering.
 *
 * The counterpart to [UserWords], and needed for the same reason in reverse. A word list built from a
 * corpus contains words this particular person will never type, and a few of them sit exactly where
 * their own words want to be: "ducking" over the swear word, "Wendy's" over a friend called Wendy,
 * "brb" over "bro". Before this the only way out was to add something else and hope it outranked them.
 *
 * Two things it deliberately does NOT do:
 *
 *  - **It doesn't make the word wrong.** A forgotten word is still a correctly spelled word, so
 *    autocorrect still leaves it alone when it is typed. Forgetting removes it as a *destination* — as
 *    something the strip offers and autocorrect arrives at — not as valid English. Treating it as a
 *    misspelling would mean typing it deliberately got it rewritten, which is a far worse outcome than
 *    the suggestion the user was trying to be rid of.
 *  - **It doesn't only cover the personal list.** Forgetting has to reach the bundled dictionary too,
 *    or the gesture is a lie on every word that isn't one of the user's own — which is most of them.
 *
 * Matching is lowercase and exact; there is no stemming, so forgetting "duck" leaves "ducking" alone.
 * That is the honest behaviour for a list the user curates by hand, and the alternative (forgetting a
 * stem and silently losing a family of words) is not something a long-press should be able to do.
 *
 * Immutable, like [UserWords]: editing builds a new instance. Small by nature — a handful of entries —
 * so a [Set] of strings is the whole implementation.
 */
class ForgottenWords private constructor(
    /** As entered, in entry order — what the settings screen lists. */
    val entries: List<String>,
    private val lower: Set<String>,
) {
    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    fun contains(word: String): Boolean = lower.isNotEmpty() && word.lowercase() in lower

    /**
     * The same test for a word still sitting in a [Dictionary]'s character array.
     *
     * The early return is the point of the overload: the scanners call this inside their hot loops, and
     * for everyone who has never forgotten a word it has to cost nothing at all — no String, no hash.
     * Dictionary words are stored lowercase already, so no lowercasing happens here either.
     */
    fun contains(source: Dictionary, index: Int): Boolean {
        if (lower.isEmpty()) return false
        return source.word(index) in lower
    }

    fun withWord(word: String): ForgottenWords {
        val w = word.trim()
        if (!UserWords.isAcceptable(w)) return this
        if (contains(w)) return this
        return of(entries + w)
    }

    fun without(word: String): ForgottenWords =
        of(entries.filterNot { it.equals(word, ignoreCase = true) })

    /** Serialised for SharedPreferences. Newline-separated, which no acceptable word can contain. */
    fun serialize(): String = entries.joinToString("\n")

    companion object {
        val EMPTY = ForgottenWords(emptyList(), emptySet())

        fun of(words: List<String>): ForgottenWords {
            val kept = ArrayList<String>()
            val lower = HashSet<String>()
            for (raw in words) {
                val w = raw.trim()
                // The same acceptability rule as the personal list, so the two screens agree about what
                // counts as a word and neither can hold something the other would refuse.
                if (!UserWords.isAcceptable(w)) continue
                if (!lower.add(w.lowercase())) continue
                kept.add(w)
            }
            if (kept.isEmpty()) return EMPTY
            return ForgottenWords(kept, lower)
        }

        fun deserialize(stored: String?): ForgottenWords {
            if (stored.isNullOrBlank()) return EMPTY
            return of(stored.split("\n"))
        }
    }
}
