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
 * All of it is ranked on the word being typed *and* the word before it — see [WordContext] and
 * [ContextRanker]. Candidates are gathered wider than the strip is and then reordered, so the pair
 * table can move a word up a couple of places without ever being able to invent one or hide the one
 * the spelling most obviously matches.
 *
 * The user's own words take part in all of it, which is the point of adding them — see [UserWords] —
 * and the words they've forgotten take part in none of it, see [ForgottenWords].
 *
 * Pure logic, no Android types.
 */
class Suggester(
    private val dict: Dictionary,
    private val corrector: Corrector,
) {
    @Volatile
    var userWords: UserWords = UserWords.EMPTY

    /** Words to leave out of every slot. Long-pressing a slot is what puts one here. */
    @Volatile
    var forgotten: ForgottenWords = ForgottenWords.EMPTY

    /** The word-pair table, or null when it hasn't loaded — in which case ranking is what it was. */
    @Volatile
    var context: ContextModel? = null

    /**
     * Suggestions for a word being typed. [prefix] is the run of letters before the cursor; empty
     * gives nothing, since there is nothing yet to predict from and a strip full of the commonest words
     * in English is just noise.
     *
     * Completions come first — while you are still typing, the word you are reaching for is far more
     * likely to be an extension of what you have than a repair of it. Corrections fill the remaining
     * slots, and only when the prefix is not itself a word: offering to "fix" something spelled right
     * is how a suggestion strip earns distrust.
     *
     * Within that, [ctx] decides the order. The user's own words stay pinned at the front, unranked:
     * someone who added "Basil" means it, and a corpus that has never seen the pair should not be able
     * to push it behind "base".
     */
    fun forPrefix(prefix: String, limit: Int = SLOTS, ctx: WordContext = WordContext.NONE): List<String> {
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
                if (forgotten.contains(w)) continue
                if (out.none { it.equals(w, ignoreCase = true) }) out.add(w)
                if (out.size >= limit) return out
            }
        }
        val room = limit - out.size
        if (room <= 0) return out

        // Gathered wider than the strip when there is context to rank on, and then reordered down to
        // [room]. Reranking three candidates barely moves anything; the nonsense suggestion the user
        // complained about is usually one the right word never even reached the strip to compete with.
        val pool = if (context != null && ctx.left != null) POOL.coerceAtLeast(room) else room
        val rest = ArrayList<String>(pool)
        for (i in dict.completions(p, pool)) {
            val w = dict.word(i)
            if (forgotten.contains(w)) continue
            if (out.none { it.equals(w, ignoreCase = true) } && rest.none { it.equals(w, ignoreCase = true) }) {
                rest.add(w)
            }
            if (rest.size >= pool) break
        }
        if (rest.size < pool && !dict.contains(p) && !userWords.contains(p)) {
            for (w in corrector.suggest(prefix, pool - rest.size, ctx)) {
                // Filtered again here rather than trusted to the corrector. The two hold the list
                // separately, and a strip that shows a forgotten word because one of them was wired up
                // and the other wasn't is the kind of bug nobody finds.
                if (forgotten.contains(w)) continue
                if (out.none { it.equals(w, ignoreCase = true) } && rest.none { it.equals(w, ignoreCase = true) }) {
                    rest.add(w)
                }
                if (rest.size >= pool) break
            }
        }
        out.addAll(ContextRanker.rerank(rest, ctx.left, context, room))
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
     *    quoting a correctly spelled word makes the strip look like it is second-guessing you;
     *  - **a prefix that is still going somewhere**, unless [WordContext.settled] says it isn't. Every
     *    unfinished word is "unknown" — `k`, `ke`, `key`, `keyb` are none of them words — so without
     *    this the strip would offer to add each of them to your dictionary as you typed `keyboard`. If
     *    the dictionary can still extend what you have, you are mid-word and not asking to keep
     *    anything.
     *
     * "Going nowhere" is what makes the slot appear at the moment it is useful: `lup` completes to lupus
     * and lupine so nothing is offered, then `lupo` completes to nothing and `"Lupo"` appears.
     *
     * On its own, though, that rule also made whole names impossible to keep, which is the gap
     * [WordContext.settled] closes. A name that happens to be the prefix of an ordinary word — Wil, Kai,
     * Cass, Ravi, Bram — is *always* going somewhere, so the slot never appeared for it and the only way
     * to add it was a trip to the settings screen. Once the word is settled, "the dictionary could still
     * extend this" is no longer a reason to stay quiet.
     */
    fun literalFor(prefix: String, ctx: WordContext = WordContext.NONE): String? {
        if (prefix.isEmpty()) return null
        if (!UserWords.isAcceptable(prefix)) return null
        val p = prefix.lowercase()
        if (dict.contains(p) || userWords.contains(prefix)) return null
        if (!ctx.settled && hasCompletions(p)) return null
        return prefix
    }

    /** Whether anything in either list extends [p]. Asks for one, since only emptiness matters. */
    private fun hasCompletions(p: String): Boolean =
        dict.completions(p, 1).isNotEmpty() ||
            userWords.dictionary?.completions(p, 1)?.isNotEmpty() == true

    /**
     * The whole strip for a word being typed: the literal first when there is one, then the ordinary
     * suggestions from [forPrefix] filling what is left.
     *
     * The literal takes a slot rather than being squeezed in beside them, so on an unknown word the
     * strip is literal + two suggestions and on a known word it is three suggestions — which is how
     * the strip stays the same width while its contents change.
     */
    fun stripFor(prefix: String, limit: Int = SLOTS, ctx: WordContext = WordContext.NONE): List<StripItem> {
        if (limit <= 0) return emptyList()
        val literal = literalFor(prefix, ctx)
        val out = ArrayList<StripItem>(limit)
        if (literal != null) out.add(StripItem(literal, literal = true))
        for (w in forPrefix(prefix, limit - out.size, ctx)) {
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

        /**
         * How many candidates to gather before reranking. Wide enough that the word which fits the
         * sentence is usually in it, narrow enough that the extra work is a few more heap insertions
         * rather than a second pass over the dictionary.
         */
        private const val POOL = 8
    }
}

/**
 * What the keyboard knows about the sentence around the word being typed.
 *
 * Bundled into one type rather than passed as loose parameters because both facts come from the same
 * place — the text immediately before the cursor — and are worked out once per keystroke by the IME,
 * which is the only part of the app that can see the field.
 */
data class WordContext(
    /**
     * The preceding word, lowercased, or null at the start of a sentence. See
     * [ContextRanker.leftContextOf], which is also what decides that a full stop ends the context.
     */
    val left: String? = null,
    /**
     * True when the word is not going to grow any further, and so the keep-as-typed slot should stop
     * waiting to see where it goes. The IME sets it for the two cases that mean that:
     *
     *  - **a capital the user put there** — a shift press in the middle of a sentence, as opposed to the
     *    auto-capitalisation the keyboard applies at a sentence start. It is the only signal available
     *    for "this is a name", and a name that is also the prefix of an ordinary word (Wil, Kai, Cass) is
     *    otherwise unkeepable from the strip, because it never stops having completions;
     *  - **a word already finished** with a space or a full stop, where there is nothing left to type.
     *
     * Kept as one flag rather than two because [Suggester] treats them identically and the difference is
     * entirely in how the IME decides, which is where each is explained.
     */
    val settled: Boolean = false,
) {
    companion object {
        /** No sentence around it: ranked on the current word alone, exactly as before this existed. */
        val NONE = WordContext()
    }
}

/**
 * One slot of the suggestion strip.
 *
 * [literal] separates "the word you typed, keep it and learn it" from an ordinary suggestion. The two
 * look different (the literal is quoted) and do different things when tapped, and the difference is
 * decided here in the pure layer rather than re-derived by comparing strings in the IME.
 *
 * [verbatim] is the third kind: text that is not a word at all and must be committed exactly — the
 * login code pinned from LightChat. It has to be its own flag rather than a special case in the IME,
 * because every ordinary path does two things to a suggestion that are wrong for a code. It takes the
 * case of whatever was typed before it, which would rewrite `G4T7QX` as `g4t7qx`; and it appends a
 * trailing space to start the next word, which in a six-character verification field is a seventh
 * character the user then has to notice and delete.
 */
data class StripItem(
    val word: String,
    val literal: Boolean = false,
    val verbatim: Boolean = false,
)
