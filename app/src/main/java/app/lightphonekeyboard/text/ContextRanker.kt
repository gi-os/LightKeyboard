package app.lightphonekeyboard.text

/**
 * Reorders candidate words by what came before them in the sentence.
 *
 * Kept apart from [ContextModel] because the table and the policy fail differently: a missing table is
 * a file problem, but "how far may context move a word" is a tuning decision that has to be arguable
 * in one place. The policy has one rule, and it is a hard rule rather than a preference:
 *
 *   **Context may reorder candidates. It may not drop the candidate the current word most obviously
 *   matches, and it may not move any candidate more than [MAX_SHIFT] places.**
 *
 * That is what stops this being worse than the bug it fixes. A ranker free to reorder without limit
 * will eventually bury the word the user is literally typing under something the corpus prefers after
 * the preceding word, and a keyboard that hides the obvious candidate is unusable in a way that a
 * keyboard offering an occasional nonsense one is not. Two things enforce it: the bonus is bounded, and
 * the incoming best candidate is put back if the reorder pushed it out of the window.
 *
 * The base score being the candidate's incoming *position* rather than a probability is deliberate too.
 * The order the strip arrives in is not one number: the user's own words are placed first on purpose,
 * completions rank above repairs on purpose, and the corrector's scores are in nats while the
 * completions' are not. Nudging the existing order keeps all of that intact and makes the effect of a
 * change something you can state in words — "a strong collocation climbs two places".
 *
 * Pure logic, no Android types.
 */
object ContextRanker {

    /**
     * How much a candidate climbs per nat of [ContextModel.pmi] — one place for every two nats it beats
     * a rival by. That exchange rate is the whole tuning, so it is worth reading against real pairs: an
     * everyday pair like "to the" scores 1.8 nats and so moves 0.9 of a place, which is deliberately
     * *not* quite enough to overtake the candidate above it on its own; "a lot" scores 4.7 and climbs
     * two; "happy birthday" and "thank you" saturate. Ordinary English barely stirs the strip and only a
     * real collocation rearranges it.
     */
    private const val PLACES_PER_NAT = 0.5f

    /**
     * The furthest any candidate may climb, whatever the pair scores.
     *
     * Not an arbitrary limit — it is what keeps the promise in the class comment. At 3.0 only the
     * candidates at positions 1 and 2 can *strictly* outscore the one at position 0 (position 3 would
     * need to climb more than three places), so the candidate the spelling liked best can never be
     * pushed past the third slot, and the strip has three.
     */
    const val MAX_SHIFT = 3.0f

    /**
     * The same, for autocorrect. Far smaller, because autocorrect commits without being asked: at 1.2
     * only the runner-up can overtake the winner at all, and only on a pair scoring above two nats. A
     * correction the user has to notice and undo costs more than a suggestion they can ignore.
     */
    const val MAX_SHIFT_CORRECTION = 1.2f

    /**
     * The best [limit] of [candidates] for a cursor sitting after [left], best first.
     *
     * Returns the plain first [limit] when there is nothing to rank on — no model, no preceding word
     * (the start of a sentence), or fewer than two candidates. Sentence starts are the first word of
     * every message and have no entry in a pair table, so they have to cost nothing and change nothing.
     */
    fun rerank(
        candidates: List<String>,
        left: String?,
        model: ContextModel?,
        limit: Int,
        maxShift: Float = MAX_SHIFT,
    ): List<String> {
        if (limit <= 0) return emptyList()
        if (model == null || left.isNullOrEmpty() || candidates.size < 2) return candidates.take(limit)
        // Stable: equal scores keep the incoming order, so a word absent from the table never overtakes
        // an equally scored neighbour just because the sort felt like it. This is also what makes the
        // cap a real bound rather than a near-miss — a candidate that ties the one above it stays below.
        val ordered = candidates.withIndex()
            .sortedByDescending { (i, w) ->
                -i + (PLACES_PER_NAT * model.pmi(left, w.lowercase())).coerceAtMost(maxShift)
            }
            .map { it.value }
        val out = ordered.take(limit)
        // The candidate the spelling alone liked best goes back in if context pushed it out of the
        // window — it is what the user is most plainly typing, and losing it is a worse bug than
        // showing a word that doesn't fit the sentence. Not applied at limit 1, where there is only one
        // slot and choosing what goes in it is the entire job being asked of the ranker.
        val obvious = candidates[0]
        if (limit >= 2 && obvious !in out) {
            return out.dropLast(1) + obvious
        }
        return out
    }

    /**
     * The word the next one has to follow, read out of the text before the cursor, or null when there
     * isn't one.
     *
     * [before] is the raw text an InputConnection hands over, which *includes* the word being typed, so
     * the partial word is skipped first.
     *
     * Null means "nothing to rank on", and it is returned for the two cases that matter as much as the
     * ordinary one:
     *
     *  - **The start of the text.** No preceding word at all.
     *  - **The start of a sentence.** After `.`, `!`, `?` or a newline the previous word belongs to a
     *    different sentence, and a pair table asked about it answers with something that sounds
     *    confident and means nothing — "day. Tomorrow" is not the bigram "day tomorrow". Sentence
     *    starts therefore rank exactly as they did before any of this existed, on word frequency alone.
     *
     * Other punctuation is transparent: a comma or a bracket interrupts a sentence without ending it,
     * and "well, I" really is the pair "well i".
     */
    fun leftContextOf(before: CharSequence?): String? {
        if (before.isNullOrEmpty()) return null
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--       // the word being typed, if any
        while (i > 0) {
            val c = before[i - 1]
            if (isWordChar(c)) break
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '\r') return null
            i--
        }
        if (i == 0) return null
        val end = i
        while (i > 0 && isWordChar(before[i - 1])) i--
        // Lowercased because the table is: "The" and "the" are the same left context.
        return before.subSequence(i, end).toString().lowercase()
    }

    /** Matches the IME's own idea of a word: letters, and the apostrophe inside a contraction. */
    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''
}
