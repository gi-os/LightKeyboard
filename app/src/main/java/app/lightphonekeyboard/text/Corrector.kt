package app.lightphonekeyboard.text

/**
 * Word-level autocorrect against the bundled [Dictionary].
 *
 * The keyboard's original autocorrect asked the phone's SpellCheckerSession, which on LightOS is not
 * installed — so it returned nothing and nothing was ever corrected. This does the work locally.
 *
 * Two ideas do most of the lifting:
 *
 *  - **The edit distance is keyboard-aware.** A plain Levenshtein distance treats "hellp"→"hello"
 *    and "hellz"→"hello" as equally likely, when the first is a finger landing one key over and the
 *    second is nothing like a real typo. Substitutions are therefore priced by how far apart the two
 *    keys actually sit ([KeyGrid], so this follows AZERTY/QWERTZ automatically), and the two error
 *    shapes a thumb typist produces constantly — a dropped/doubled repeat letter and a transposition
 *    from rolling two keys — are discounted.
 *  - **Frequency breaks the ties.** Real typos usually sit one edit from several dictionary words; the
 *    common one is nearly always the intended one. Candidates are ranked on
 *    `ln P(word) − LAMBDA × editCost`, which is a log-posterior under a noisy-channel model.
 *
 * A word that is already in the dictionary is never touched. Correction only happens once a word is
 * finished, so nothing rewrites itself under the cursor mid-word.
 *
 * Pure logic, no Android types — [CorrectorTest] exercises it on the JVM.
 */
class Corrector(
    private val dict: Dictionary,
    grid: KeyGrid = KeyGrid.qwerty(),
) {
    /** Swapped in whenever the view relays out (height preset or layout change). */
    @Volatile
    var grid: KeyGrid = grid

    /** The user's own words. Scanned as a second pass, so the 60k-word hot loop stays untouched. */
    @Volatile
    var userWords: UserWords = UserWords.EMPTY

    /**
     * The correction for [typed], or null to leave it exactly as typed. Case is not considered here —
     * the caller re-applies the user's capitalisation.
     */
    fun correct(typed: String): String? = suggest(typed, 1).firstOrNull()

    /**
     * Up to [limit] replacements for [typed], best first. Empty when the word should be left alone:
     * it is already a real word, too short to correct safely, or nothing plausible is near it.
     */
    fun suggest(typed: String, limit: Int = 3): List<String> {
        val w = typed.lowercase()
        val n = w.length
        if (n < MIN_LENGTH || n > MAX_LENGTH) return emptyList()
        // Anything with a digit or an inner capital is a deliberate token (mp3, covid19, iPhone,
        // an acronym) rather than a misspelling. Same guard the original spell-check path used.
        if (typed.any { it.isDigit() }) return emptyList()
        if (typed.drop(1).any { it.isUpperCase() }) return emptyList()
        if (w.any { it !in 'a'..'z' && it != '\'' }) return emptyList()
        // A word the user added is a real word by definition. This is the whole reason the personal
        // list exists: "Basil" is in no spelling dictionary, so without this it gets rewritten to
        // "Basic" every time it is typed.
        if (dict.contains(w) || userWords.contains(w)) return emptyList()

        val budget = budgetFor(n)
        val typedMask = Dictionary.maskOf(w)
        // A word differing by k edits can differ by at most k letters in either direction, so the
        // letter-set mismatch is a valid lower bound on the edit count — and it costs two popcounts
        // instead of a DP pass. This is what keeps a scan of ~50k candidates inside a frame.
        val maxLetterMismatch = budget.toInt() + 1

        val heap = TopK(limit)
        scan(dict, MAIN, w, n, typedMask, maxLetterMismatch, budget, heap)
        userWords.dictionary?.let { scan(it, USER, w, n, typedMask, maxLetterMismatch, budget, heap) }
        return heap.words(dict, userWords)
    }

    /** Score every plausible candidate in [source] into [heap]. Shared by the bundled and user lists. */
    private fun scan(
        source: Dictionary,
        tag: Int,
        typed: String,
        n: Int,
        typedMask: Int,
        maxLetterMismatch: Int,
        budget: Float,
        heap: TopK,
    ) {
        for (i in source.lengthRange(n - MAX_LENGTH_DELTA, n + MAX_LENGTH_DELTA)) {
            val m = source.mask(i)
            if (Integer.bitCount(m and typedMask.inv()) > maxLetterMismatch) continue
            if (Integer.bitCount(typedMask and m.inv()) > maxLetterMismatch) continue
            val cost = editCost(typed, source, i, budget)
            if (cost > budget) continue
            heap.offer(tag, i, source.logFreq(i) - LAMBDA * cost)
        }
    }

    /**
     * How much total edit cost a word of length [n] may absorb before we stop believing it. Longer
     * words get more room because there is more of them to get wrong, and because a long word with two
     * slips in it is still unambiguous, where a three-letter word with two slips is anything at all.
     */
    private fun budgetFor(n: Int): Float = when {
        n <= 4 -> 1.05f     // one edit of any kind, and nothing more
        n <= 7 -> 1.70f     // one clear edit, or two cheap ones (a repeat slip plus a near-key slip)
        else -> 2.30f
    }

    // ---------------------------------------------------------------- weighted edit distance

    // Reused across calls so a scan doesn't allocate per candidate. Corrector is only ever touched
    // from the IME thread; the only shared-mutable field is [grid], which is @Volatile.
    private val prev = FloatArray(MAX_LENGTH + 2)
    private val cur = FloatArray(MAX_LENGTH + 2)
    private val prev2 = FloatArray(MAX_LENGTH + 2)

    /**
     * Damerau-Levenshtein between [typed] and dictionary word [wi], with keyboard-weighted
     * substitutions, discounted repeat-letter slips, and early abandonment once every cell in a row
     * exceeds [budget] (no later row can come back down, since costs are non-negative).
     */
    internal fun editCost(typed: String, source: Dictionary, wi: Int, budget: Float): Float {
        val n = typed.length
        val m = source.length(wi)
        val g = grid
        for (j in 0..m) prev[j] = j * INDEL
        var pRow = prev
        var p2Row = prev2
        var cRow = cur

        for (i in 1..n) {
            val a = typed[i - 1]
            cRow[0] = i * INDEL
            var rowMin = cRow[0]
            for (j in 1..m) {
                val b = source.charAt(wi, j - 1)
                // Substitution priced by key separation: landing one key over is a common slip,
                // landing across the keyboard is not a slip at all.
                val sub = pRow[j - 1] + if (a == b) 0f else subCost(g, a, b)
                // Deleting a typed char that repeats its neighbour, or inserting one that repeats
                // the candidate's, is the "helo"/"belive" class of error — the most common of all.
                val delRepeat = i > 1 && typed[i - 2] == a
                val insRepeat = j > 1 && source.charAt(wi, j - 2) == b
                val del = cRow[j - 1] + if (insRepeat) REPEAT else INDEL
                val ins = pRow[j] + if (delRepeat) REPEAT else INDEL
                var v = if (sub < del) sub else del
                if (ins < v) v = ins
                // Transposition: two keys rolled in the wrong order ("teh").
                if (i > 1 && j > 1 && a == source.charAt(wi, j - 2) && typed[i - 2] == b) {
                    val t = p2Row[j - 2] + TRANSPOSE
                    if (t < v) v = t
                }
                cRow[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > budget) return Float.MAX_VALUE   // abandon: it can only get worse
            val spare = p2Row
            p2Row = pRow
            pRow = cRow
            cRow = spare
        }
        return pRow[m]
    }

    /**
     * Cost of typing [b] but hitting [a]. Neighbouring keys are cheap, anything beyond about two keys
     * away is a full unit — past that point it is not a spatial slip and the distance shouldn't keep
     * growing, or a wildly wrong long word could still beat a close short one on frequency alone.
     */
    private fun subCost(g: KeyGrid, a: Char, b: Char): Float {
        val d = g.distance(a, b)
        if (d == Float.MAX_VALUE) return 1f          // letter not on this layout — treat as a full edit
        val scaled = SUB_FLOOR + SUB_SLOPE * d
        return if (scaled > 1f) 1f else scaled
    }

    /**
     * A tiny fixed-size max-heap-by-score. [limit] is 1-3, so linear insertion is the cheap choice.
     * Entries are held as (source tag, index) rather than as strings, so a candidate that merely beats
     * the current worst doesn't allocate — only the handful that survive to the end are materialised.
     */
    private class TopK(private val limit: Int) {
        private val src = IntArray(limit)
        private val idx = IntArray(limit) { -1 }
        private val score = FloatArray(limit) { -Float.MAX_VALUE }

        fun offer(tag: Int, i: Int, s: Float) {
            if (s <= score[limit - 1]) return
            var p = limit - 1
            while (p > 0 && score[p - 1] < s) {
                score[p] = score[p - 1]; idx[p] = idx[p - 1]; src[p] = src[p - 1]; p--
            }
            score[p] = s; idx[p] = i; src[p] = tag
        }

        fun words(dict: Dictionary, user: UserWords): List<String> {
            val out = ArrayList<String>(limit)
            for (k in 0 until limit) {
                if (idx[k] < 0) continue
                out.add(
                    if (src[k] == USER) {
                        // Give back the capitalisation the user typed — the point of adding a name.
                        val w = user.dictionary?.word(idx[k]) ?: continue
                        user.displayOf(w)
                    } else {
                        dict.word(idx[k])
                    },
                )
            }
            return out
        }
    }

    companion object {
        private const val MAIN = 0
        private const val USER = 1

        /** Below this we don't guess: a two-letter typo has too many equally good readings. */
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 24
        /** Candidates may differ from the typed length by at most this much. */
        private const val MAX_LENGTH_DELTA = 2

        /**
         * The noisy-channel weights. These were fitted, not guessed: tools/../CorrectorTest holds a
         * corpus of real typos that must be fixed and the 300 commonest words that must NOT be
         * touched, and these are the values that get both right. Two of them are worth explaining,
         * because the obvious settings are wrong:
         *
         *  - [INDEL] is *below* a full unit and below the cost of a far-key substitution. Simply
         *    missing a key is one of the most common things a thumb does on a small keyboard, so
         *    "grat" is far more likely to be "great" with a letter lost than "gray" with one
         *    mistyped — even though the substitution is the smaller edit by plain Levenshtein.
         *  - [LAMBDA] has to be high enough that these cost differences survive the frequency prior.
         *    At a lower value "helo" corrected to "help", which is 18× commoner than "hello" in the
         *    corpus and one adjacent-key slip away — frequency was simply drowning the cost signal.
         */
        private const val LAMBDA = 8.0f       // nats of log-probability per unit of edit cost
        private const val INDEL = 0.75f       // a letter missed out, or an extra one landed
        private const val REPEAT = 0.20f      // dropped or doubled repeat letter ("helo", "occurence")
        private const val TRANSPOSE = 0.55f   // rolled two keys the wrong way round ("teh")
        private const val SUB_FLOOR = 0.30f   // cost of hitting the key right next door
        private const val SUB_SLOPE = 0.35f   // added per key unit of separation, capped at 1.0
    }
}
