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
 *  - The **alignment channel** ([alignCost]) walks the traced samples against the word's letters in
 *    order, letting the trace linger on a letter but never go back, and charges the distance from each
 *    sample to the letter it lands on. This is the discriminating one: it asks whether the finger
 *    actually went *through these keys in this sequence*, which is the question that matters. A plain
 *    average distance between the two paths — which is all this had at first — cannot answer it, and
 *    happily matched "page" to "posts" because both sweep from the right side of the keyboard to the
 *    left and back.
 *  - The **shape channel** compares the paths after centring and scaling both, so it sees the form of
 *    the stroke and ignores where it was drawn. This is what forgives a gesture traced small, or
 *    drifting off toward one side, which the alignment channel alone punishes.
 *  - A cheap **mean-distance** pass runs first, purely to reject candidates nowhere near the trace
 *    before paying for the alignment DP.
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
    fun decode(px: FloatArray, py: FloatArray, count: Int, limit: Int = 4): List<String> {
        if (count < 3) return emptyList()
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

        val heap = TopK(limit)
        for (i in dict.lengthRange(MIN_WORD, Corrector.MAX_LENGTH)) {
            if (!dict.isAlphaOnly(i)) continue                        // can't trace an apostrophe
            val len = dict.length(i)
            if (bit(dict.charAt(i, 0)) and startMask == 0) continue
            if (bit(dict.charAt(i, len - 1)) and endMask == 0) continue
            if (dict.mask(i) and corridor.inv() != 0) continue        // a letter the path never neared

            val n = buildTemplate(g, i, len)
            if (n < 2) continue
            resample(kx, ky, n, tx, ty)
            val location = meanDistance(ux, uy, tx, ty)
            if (location > LOCATION_CUTOFF) continue                  // nowhere near: reject cheaply
            normalize(tx, ty, nx, ny)
            val shape = meanDistance(sx, sy, nx, ny)
            heap.offer(i, dict.logFreq(i) - W_LOCATION * location - W_SHAPE * shape)
        }
        return heap.words(dict)
    }

    private fun bit(c: Char): Int = if (c in 'a'..'z') 1 shl (c - 'a') else 0

    /**
     * The candidate's key centres, written into [kx]/[ky]; returns how many. Consecutive repeated
     * letters collapse to one point — "hello" has its two l's in the same place, and leaving both in
     * would stack two template samples and distort the resampling.
     */
    private fun buildTemplate(g: KeyGrid, wi: Int, len: Int): Int {
        var n = 0
        var last = ' '
        for (k in 0 until len) {
            val c = dict.charAt(wi, k)
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

    /** Fixed-size top-[limit] by score; [limit] is 3-5, so linear insertion beats a real heap. */
    private class TopK(private val limit: Int) {
        private val idx = IntArray(limit) { -1 }
        private val score = FloatArray(limit) { -Float.MAX_VALUE }

        fun offer(i: Int, s: Float) {
            if (s <= score[limit - 1]) return
            var p = limit - 1
            while (p > 0 && score[p - 1] < s) {
                score[p] = score[p - 1]; idx[p] = idx[p - 1]; p--
            }
            score[p] = s; idx[p] = i
        }

        fun words(dict: Dictionary): List<String> {
            val out = ArrayList<String>(limit)
            for (k in 0 until limit) if (idx[k] >= 0) out.add(dict.word(idx[k]))
            return out
        }
    }

    companion object {
        /** Points each path is resampled to. 32 is SHARK²'s figure and plenty for word-sized strokes. */
        const val SAMPLES = 32
        /** Shortest word a gesture may produce. One letter is a tap, and two-letter strokes are so
         *  ambiguous that allowing them mostly means stealing taps that drifted. */
        const val MIN_WORD = 3
        /** Below this total path length it isn't a gesture — the view uses the same figure to decide
         *  whether a drag became a swipe at all. */
        const val MIN_PATH_LENGTH = 1.2f

        private const val END_RADIUS = 1.60f        // how far the first/last letter may sit from the ends
        private const val CORRIDOR_RADIUS = 1.70f   // how far off the traced path a letter may sit
        private const val LOCATION_CUTOFF = 2.2f    // mean per-sample miss beyond which we stop scoring

        // Relative pull of the two geometry terms, in nats of log-probability per key unit of error.
        // The frequency prior has no weight of its own — it enters at its natural scale, and is
        // therefore the weakest of the three, so a common word can't overrule a clearly-drawn rare one.
        // Fitted against the synthetic-trace benchmark in GestureDecoderTest, not picked by eye.
        private const val W_LOCATION = 4.0f
        private const val W_SHAPE = 16.0f
    }
}
