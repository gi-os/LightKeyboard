package app.lightphonekeyboard.text

/**
 * The words you added yourself — names, places, anything the bundled list has never heard of.
 *
 * Without this, a name is unusable on a keyboard that corrects: "Basil" isn't in any spelling
 * dictionary, so autocorrect rewrites it to "Basic" or "Basin" every single time, and swipe typing
 * can never produce it at all. Adding it here fixes all three uses at once — it stops being
 * corrected away, becomes something a correction can arrive *at*, and becomes traceable.
 *
 * Words are held in a [Dictionary] built by [Dictionary.of], deliberately the same structure as the
 * bundled list, so the corrector and the swipe decoder scan them with the same code rather than
 * growing a parallel path for a handful of entries.
 *
 * Matching is lowercase, but the form you typed is what gets inserted — [displayOf] maps back. That is
 * the point for a name: type "bas", get "Basil", capital included.
 *
 * Immutable. Editing the list builds a new instance ([withWord] / [without]).
 */
class UserWords private constructor(
    /** As entered, in entry order — what the settings screen lists. */
    val entries: List<String>,
    private val display: Map<String, String>,
    /** The same words, lowercased, in searchable form. Null when there are none. */
    val dictionary: Dictionary?,
) {
    val size: Int get() = entries.size

    fun contains(word: String): Boolean = display.containsKey(word.lowercase())

    /** The form the user typed for [word] (matched case-insensitively), or [word] unchanged. */
    fun displayOf(word: String): String = display[word.lowercase()] ?: word

    fun withWord(word: String): UserWords {
        val w = word.trim()
        if (!isAcceptable(w)) return this
        if (contains(w)) return this
        return of(entries + w)
    }

    fun without(word: String): UserWords =
        of(entries.filterNot { it.equals(word, ignoreCase = true) })

    /** Serialised for SharedPreferences. Newline-separated, which no acceptable word can contain. */
    fun serialize(): String = entries.joinToString("\n")

    companion object {
        /**
         * How common a user word is treated as being. It has to be high enough that a name beats the
         * real words crowding around it — typing "bas" should offer "Basil" above "base" — but it is
         * still only a prior, so it competes on spelling distance like everything else rather than
         * winning outright. Roughly the frequency of a word you'd meet a few times a day.
         */
        const val LOG_FREQ = -7.0f

        /** Longest word worth storing; also what the scorers' scratch buffers allow. */
        const val MAX_LENGTH = Dictionary.MAX_WORD

        val EMPTY = UserWords(emptyList(), emptyMap(), null)

        /**
         * Only letters and an apostrophe, and long enough to be worth a slot. Rejecting the rest here
         * rather than at the UI keeps the rule in one place: a word the keyboard cannot type is a word
         * it can never suggest, so storing it would just be a confusing no-op in the list.
         */
        fun isAcceptable(word: String): Boolean {
            val w = word.trim()
            return w.length in 2..MAX_LENGTH && w.all { it.isLetter() || it == '\'' }
        }

        fun of(words: List<String>): UserWords {
            val kept = ArrayList<String>()
            val display = HashMap<String, String>()
            for (raw in words) {
                val w = raw.trim()
                if (!isAcceptable(w)) continue
                val key = w.lowercase()
                if (display.containsKey(key)) continue
                display[key] = w
                kept.add(w)
            }
            if (kept.isEmpty()) return EMPTY
            val dict = Dictionary.of(kept.map { it.lowercase() to LOG_FREQ })
            return UserWords(kept, display, dict)
        }

        fun deserialize(stored: String?): UserWords {
            if (stored.isNullOrBlank()) return EMPTY
            return of(stored.split("\n"))
        }
    }
}
