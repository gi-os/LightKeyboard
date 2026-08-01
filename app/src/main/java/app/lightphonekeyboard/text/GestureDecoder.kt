package app.lightphonekeyboard.text

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns a traced finger path into words — swipe typing.
 *
 * The approach follows SHARK² (Kristensson & Zhai, UIST'04), which is still what production gesture
 * keyboards are built on. For every word in the dictionary you can draw its *ideal* path: the polyline
 * through its letters' key centres. Recognising a gesture is then asking which ideal path the traced
 * one most resembles. Three measurements are combined, because each covers the others' blind spots:
 *
 *  - The **location channel** compares the two paths sample by sample where they were actually drawn.
 *    It is also the cheap rejection test: a candidate whose mean per-sample miss is hopeless is
 *    dropped before anything else is computed.
 *  - The **shape channel** compares the paths after centring and scaling both, so it sees the form of
 *    the stroke and ignores where it was drawn. This is what forgives a gesture traced small, or
 *    drifting off toward one side, which the location channel alone punishes.
 *  - The **length channel** charges a candidate for the distance the finger travelled that its own
 *    ideal path cannot account for. Only the excess is charged, never the shortfall: a thumb cuts
 *    corners and traces small, so travelling *less* than the ideal path is ordinary, while travelling
 *    a whole key further than the candidate explains means the finger went somewhere the candidate
 *    doesn't. It is what separates "here" from "he" and "use" from "us" — pairs the other two channels
 *    barely distinguish, because the shorter word's straight line lies along the longer word's path.
 *
 * A frequency prior settles the rest: when two words really do have near-identical paths ("for"/"fir",
 * "sun"/"sum") the common one wins, which is right often enough that backspacing to cycle the
 * alternatives is a fair trade.
 *
 * Everything happens in *key units* (see [KeyGrid]) so the tuning constants below mean "a fraction of
 * a key" and hold across the three height presets and all three letter layouts.
 *
 * Pure logic, no Android types — GestureDecoderTest drives it on the JVM.
 */
class GestureDecoder(
    private val dict: Dictionary,
    grid: KeyGrid = KeyGrid.qwerty(),
) {
    @Volatile
    var grid: KeyGrid = grid

    /** The user's own words, traced like any other. Scanned as a second, tiny pass. */
    @Volatile
    var userWords: UserWords = UserWords.EMPTY

    /** Words the user has forgotten. A trace never resolves to one — see [ForgottenWords]. */
    @Volatile
    var forgotten: ForgottenWords = ForgottenWords.EMPTY

    /** The word-pair table, or null when it hasn't loaded — in which case ranking is what it was. */
    @Volatile
    var context: ContextModel? = null

    // Resampled input path (location channel) and its centred/scaled copy (shape channel).
    private val ux = FloatArray(SAMPLES)
    private val uy = FloatArray(SAMPLES)
    private val sx = FloatArray(SAMPLES)
    private val sy = FloatArray(SAMPLES)
    // The same two channels for the candidate template currently being scored.
    private val tx = FloatArray(SAMPLES)
    private val ty = FloatArray(SAMPLES)
    private val nx = FloatArray(SAMPLES)
    private val ny = FloatArray(SAMPLES)
    // The candidate's letter centres, with consecutive repeats collapsed (see [buildTemplate]).
    private val kx = FloatArray(Corrector.MAX_LENGTH)
    private val ky = FloatArray(Corrector.MAX_LENGTH)

    /**
     * Decode a traced path into at most [limit] candidate words, best first. [px]/[py] hold the first
     * [count] raw touch points in key units. Returns empty if the path is too short to be a gesture
     * or nothing in the dictionary fits it.
     */
    fun decode(
        px: FloatArray,
        py: FloatArray,
        count: Int,
        limit: Int = 4,
        ctx: WordContext = WordContext.NONE,
    ): List<String> {
        if (count < 2) return emptyList()
        val pathLength = resample(px, py, count, ux, uy)
        if (pathLength < MIN_PATH_LENGTH) return emptyList()
        normalize(ux, uy, sx, sy)

        val g = grid
        // Pruning. A candidate has to start near where the finger went down, end near where it came
        // up, and use only letters the path actually passed through. These are three integer tests,
        // which is what lets the whole 80k-word list be considered on every gesture.
        val startMask = g.lettersNear(ux[0], uy[0], END_RADIUS)
        val endMask = g.lettersNear(ux[SAMPLES - 1], uy[SAMPLES - 1], END_RADIUS)
        if (startMask == 0 || endMask == 0) return emptyList()
        var corridor = 0
        for (i in 0 until SAMPLES) corridor = corridor or g.lettersNear(ux[i], uy[i], CORRIDOR_RADIUS)

        // Whether the stroke went from one key to a different one, which is the whole of what separates
        // "of" from a tap on o whose finger wandered. Nearest key, not the end masks, because those are
        // deliberately generous (1.6 key units) and a drifted tap satisfies both of them with the same
        // letter. Only two-letter readings are gated on it: a three-letter word has a corner to prove
        // itself with, and a stroke this short has nothing but its two ends.
        val twoKeys = g.nearest(ux[0], uy[0]) != g.nearest(ux[SAMPLES - 1], uy[SAMPLES - 1])
        val shortest = if (twoKeys) MIN_WORD else MIN_WORD_ONE_KEY

        val heap = TopK(limit)
        val user = userWords
        scan(dict, MAIN, g, startMask, endMask, corridor, shortest, pathLength, heap)
        user.dictionary?.let { scan(it, USER, g, startMask, endMask, corridor, shortest, pathLength, heap) }
        // Reordered by the preceding word, which is where a swipe needs it most: near-identical traces
        // ("way"/"wag", "sun"/"sum") are settled by frequency alone, and after "on my" the corpus has an
        // opinion worth more than that. Held to the correction bound, not the strip's, because the best
        // reading is committed on lift without being asked.
        val words = ContextRanker.rerank(
            heap.words(dict, user), ctx.left, context, limit, ContextRanker.MAX_SHIFT_CORRECTION,
        )
        // The bundled dictionary has no apostrophes in it at all — contractions are stored plain
        // ("im", "dont", "cant"; see tools/gen_dict.py's EXTRA list) because nobody swipes or types an
        // apostrophe. So nothing above can ever produce "I'm" as text; "im" is what comes out, and it
        // needs the capital a dictionary lookup can't supply — the shift state only knows a *sentence*
        // is starting, and the first person is capital mid-sentence too. See `scan` for why "im" is
        // reachable here at all: on its own it is the rarest tail end of the two-letter list, kept
        // unreachable like the rest of that tail: this is the one exception, for the one word that
        // stands in for one of the commonest words there is.
        return if (words.contains(IM)) words.map { if (it == IM) "I'm" else it } else words
    }

    /** Score every candidate in [source] that survives pruning. Shared by the bundled and user lists. */
    private fun scan(
        source: Dictionary,
        tag: Int,
        g: KeyGrid,
        startMask: Int,
        endMask: Int,
        corridor: Int,
        shortest: Int,
        pathLength: Float,
        heap: TopK,
    ) {
        for (i in source.lengthRange(shortest, Corrector.MAX_LENGTH)) {
            if (!source.isAlphaOnly(i)) continue                        // can't trace an apostrophe
            val len = source.length(i)
            // Two letters only if it is one of the common ones. This allowlist is the whole of the
            // strictness: an extra distance requirement for neighbouring keys was tried and reverted,
            // because half the words worth swiping are neighbour pairs — we, as, an, am, or, up — and
            // making those harder is the opposite of the point. Naming the words is what separates a
            // deliberate "as" from a slip that happens to land on s. The dictionary's two-letter list is
            // already curated to what a Scrabble dictionary allows, but its tail — ax, yo, pa, un, im,
            // oh, lo, aw, re, id, ma, ex — is rarer than the accidental two-key drag it would be
            // answering, so admitting it trades one wrong word for another. A penalty was not enough;
            // these have to be unreachable — except "im", let back in below on different terms: not
            // because it is common on its own (it is the rarest word in the tail, per the dictionary's
            // own -13.0 floor for it — see IM_LOGF), but because it is the only way to reach "I'm",
            // which the dictionary doesn't store at all (no apostrophes in it — see `decode`). Scored
            // on IM_LOGF instead of its real entry so it competes as what it actually represents.
            val isIm = len == 2 && source.charAt(i, 0) == 'i' && source.charAt(i, 1) == 'm'
            if (len == 2 && !isCommonTwo(source, i) && !isIm) continue
            if (bit(source.charAt(i, 0)) and startMask == 0) continue
            if (bit(source.charAt(i, len - 1)) and endMask == 0) continue
            if (source.mask(i) and corridor.inv() != 0) continue        // a letter the path never neared

            val n = buildTemplate(g, source, i, len)
            if (n < 2) continue
            val templateLength = resample(kx, ky, n, tx, ty)
            val location = meanDistance(ux, uy, tx, ty)
            if (location > LOCATION_CUTOFF) continue                    // nowhere near: reject cheaply
            normalize(tx, ty, nx, ny)
            val shape = meanDistance(sx, sy, nx, ny)
            // Last, so a forgotten word costs a String only once a trace has actually landed near it.
            if (forgotten.contains(source, i)) continue
            val excess = (pathLength - templateLength).coerceAtLeast(0f)
            val shortWord = if (len == 2) SHORT_PENALTY else 0f
            val freq = if (isIm) IM_LOGF else source.logFreq(i)
            heap.offer(
                tag, i,
                freq - W_LOCATION * location - W_SHAPE * shape - W_EXCESS * excess - shortWord,
            )
        }
    }

    private fun bit(c: Char): Int = if (c in 'a'..'z') 1 shl (c - 'a') else 0

    /** Whether the two-letter word at [i] is one a swipe may produce. */
    private fun isCommonTwo(source: Dictionary, i: Int): Boolean {
        val a = source.charAt(i, 0)
        val b = source.charAt(i, 1)
        if (a !in 'a'..'z' || b !in 'a'..'z') return false
        return COMMON_TWO_BITS[a - 'a'] and (1 shl (b - 'a')) != 0
    }

    /**
     * The candidate's key centres, written into [kx]/[ky]; returns how many. Consecutive repeated
     * letters collapse to one point — "hello" has its two l's in the same place, and leaving both in
     * would stack two template samples and distort the resampling.
     */
    private fun buildTemplate(g: KeyGrid, source: Dictionary, wi: Int, len: Int): Int {
        var n = 0
        var last = ' '
        for (k in 0 until len) {
            val c = source.charAt(wi, k)
            if (c == last) continue
            if (!g.has(c)) return 0          // letter missing from this layout
            kx[n] = g.x(c)
            ky[n] = g.y(c)
            n++
            last = c
        }
        return n
    }

    /**
     * Walk a polyline and lay [SAMPLES] equally spaced points along it, into [outX]/[outY]. Returns
     * the polyline's total length. Equal spacing is what makes two paths comparable point-by-point
     * regardless of how fast the finger moved or how many touch events the digitiser reported.
     */
    private fun resample(
        px: FloatArray, py: FloatArray, count: Int, outX: FloatArray, outY: FloatArray,
    ): Float {
        var total = 0f
        for (i in 1 until count) total += dist(px[i - 1], py[i - 1], px[i], py[i])
        if (total <= 1e-6f) {   // a point, not a path: every sample sits on it
            for (i in 0 until SAMPLES) { outX[i] = px[0]; outY[i] = py[0] }
            return 0f
        }
        val step = total / (SAMPLES - 1)
        outX[0] = px[0]
        outY[0] = py[0]
        var out = 1
        var seg = 1
        var segStartX = px[0]
        var segStartY = py[0]
        var travelled = 0f
        while (out < SAMPLES - 1 && seg < count) {
            val segLen = dist(segStartX, segStartY, px[seg], py[seg])
            if (travelled + segLen < step) {
                travelled += segLen
                segStartX = px[seg]
                segStartY = py[seg]
                seg++
                continue
            }
            // The next sample falls inside this segment: step into it and restart the walk there.
            val t = (step - travelled) / segLen
            val stepX = segStartX + (px[seg] - segStartX) * t
            val stepY = segStartY + (py[seg] - segStartY) * t
            outX[out] = stepX
            outY[out] = stepY
            out++
            segStartX = stepX
            segStartY = stepY
            travelled = 0f
        }
        // Float drift can leave the last sample or two unfilled; pin them to the path's end.
        while (out < SAMPLES) { outX[out] = px[count - 1]; outY[out] = py[count - 1]; out++ }
        return total
    }

    /** Centre on the path's centroid and scale it to unit extent — the shape channel. */
    private fun normalize(
        inX: FloatArray, inY: FloatArray, outX: FloatArray, outY: FloatArray,
    ) {
        var cx = 0f
        var cy = 0f
        for (i in 0 until SAMPLES) { cx += inX[i]; cy += inY[i] }
        cx /= SAMPLES
        cy /= SAMPLES
        var maxAbsX = 0f
        var maxAbsY = 0f
        for (i in 0 until SAMPLES) {
            val ax = abs(inX[i] - cx)
            val ay = abs(inY[i] - cy)
            if (ax > maxAbsX) maxAbsX = ax
            if (ay > maxAbsY) maxAbsY = ay
        }
        // Both axes share one scale factor, so a stroke's aspect ratio survives normalisation —
        // scaling each axis independently would make a horizontal swipe and a vertical one identical.
        val extent = if (maxAbsX > maxAbsY) maxAbsX else maxAbsY
        val s = if (extent > 1e-4f) 1f / extent else 0f
        for (i in 0 until SAMPLES) {
            outX[i] = (inX[i] - cx) * s
            outY[i] = (inY[i] - cy) * s
        }
    }

    private fun meanDistance(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray): Float {
        var sum = 0f
        for (i in 0 until SAMPLES) sum += dist(ax[i], ay[i], bx[i], by[i])
        return sum / SAMPLES
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Fixed-size top-[limit] by score; [limit] is 3-5, so linear insertion beats a real heap. Entries
     * are (source tag, index) rather than strings, so scoring never allocates.
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
                        val w = user.dictionary?.word(idx[k]) ?: continue
                        user.displayOf(w)   // the capitalisation the user typed
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

        /** Points each path is resampled to. 32 is SHARK²'s figure and plenty for word-sized strokes. */
        const val SAMPLES = 32
        /**
         * Shortest word a gesture may produce. Two, not three: "of", "to", "in", "is", "it" and "on"
         * are all in the twenty commonest words in English, and a three-letter floor meant every one of
         * them decoded to something else — "of" came out "off", "to" came out "top" — or, when the two
         * keys were neighbours, to nothing at all, with the first letter already retracted.
         *
         * One letter stays impossible on purpose: "a" and "I" are taps, and a gesture that could return
         * a single letter would make every drifted tap a coin toss.
         */
        /**
         * The only two-letter words a swipe may produce, as a 26x26 bitmap indexed by first letter.
         *
         * A bitmap and not a Set<String> because the membership test runs once per candidate, tens of
         * thousands of times per gesture, and must not build a String to do it.
         */
        private val COMMON_TWO_BITS = IntArray(26).also { rows ->
            // Exactly the set `decodes two-letter words` pins, plus "hi". Kept in step with that test
            // deliberately: it is the list of words judged worth swiping, and a word missing from here
            // is a word that silently stops being reachable.
            for (word in listOf(
                "of", "to", "in", "is", "it", "on", "we", "or", "by", "up", "as", "my", "be",
                "no", "so", "go", "ok", "me", "he", "us", "an", "if", "do", "at", "am", "ah", "hi",
            )) {
                rows[word[0] - 'a'] = rows[word[0] - 'a'] or (1 shl (word[1] - 'a'))
            }
        }

        /** The one two-letter reading `scan` admits without being in [COMMON_TWO_BITS]. See `scan`. */
        private const val IM = "im"

        /**
         * What "im" is scored at when read as "I'm", in place of its real dictionary entry.
         *
         * The dictionary gives "im" -13.0 — tools/gen_dict.py's flat floor for chat shorthand no
         * speller lists, calibrated for "im" typed as itself, which is close to never. Read as "I'm" it
         * is nowhere near that rare, so scoring it on the floor loses every time to whatever plausible
         * three-letter word sits near the same trace: "him" (-7.86), "ibm" (-9.56), "jim" (-9.60), aim
         * (-9.89) all beat -13.0 comfortably, which is the bug this constant fixes ("im" swiping to
         * "jim"). -5.5 sits above all of them with room to spare, in the "he"/"we"/"my"/"us" band —
         * plausible company for one of the commonest contractions in typed English, and short of
         * true top-tier words like "is" or "it" so it doesn't start winning traces that aren't its own.
         * Figures are from the bundled app/src/main/res/raw/words.bin, not guessed.
         */
        private const val IM_LOGF = -5.5f

        const val MIN_WORD = 2
        /** The floor when the stroke started and finished on the same key — see [decode]. */
        const val MIN_WORD_ONE_KEY = 3
        /**
         * Below this total path length it isn't a gesture at all.
         *
         * It has to sit under one key unit, or two-letter strokes between neighbouring keys ("we", "as",
         * "ok") never reach the dictionary: their whole path is the gap between two key centres, about
         * one unit, and a finger cuts that short at both ends. The old figure was above it, which is why
         * those words produced nothing whatsoever. This is the view's own trace-start distance (22dp of
         * about a 40dp key), so the two thresholds now agree — below this the view would never have
         * called the drag a swipe, and nothing that it does call a swipe is thrown away here.
         */
        const val MIN_PATH_LENGTH = 0.5f

        private const val END_RADIUS = 1.60f        // how far the first/last letter may sit from the ends
        private const val CORRIDOR_RADIUS = 1.70f   // how far off the traced path a letter may sit
        private const val LOCATION_CUTOFF = 2.2f    // mean per-sample miss beyond which we stop scoring

        // Relative pull of the two geometry terms, in nats of log-probability per key unit of error.
        // The frequency prior has no weight of its own — it enters at its natural scale, and is
        // therefore the weakest of the three, so a common word can't overrule a clearly-drawn rare one.
        // Fitted against the synthetic-trace benchmark in GestureDecoderTest, not picked by eye.
        private const val W_LOCATION = 4.0f
        private const val W_SHAPE = 16.0f
        /** Per key unit of travel the candidate's own path cannot account for — the length channel. */
        private const val W_EXCESS = 0.5f

        /**
         * What a two-letter reading has to beat the field by, in nats.
         *
         * A two-letter template is a straight line between two keys, and a straight line is the one
         * shape every stroke contains somewhere: with no corner to prove itself with, "or" fits a trace
         * of "our" about as well as "our" does, and "of" fits "off" exactly (the repeated letter
         * collapses, so the two templates are the same line). Frequency then hands it to the two-letter
         * word every time, which is how allowing them at all costs three-letter accuracy.
         *
         * So they are allowed, but made to clear the field rather than tie it. 0.75 nats is roughly
         * "twice as likely on frequency alone", and it is fitted, not guessed: on the synthetic-trace
         * benchmark in GestureDecoderTest it is the largest value that still decodes 23 of the 26
         * commonest two-letter words on the first guess, and it leaves three-letter accuracy where it
         * was before two-letter words were scored at all.
         */
        private const val SHORT_PENALTY = 0.75f
    }
}
