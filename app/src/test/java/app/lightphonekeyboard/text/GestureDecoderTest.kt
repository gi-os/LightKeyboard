package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Swipe decoding. Real gestures are traced by a thumb, not drawn with a ruler, so the tests here
 * synthesise the sloppy version: the ideal polyline through a word's keys, walked at a coarse step,
 * with the corners rounded off (a finger doesn't stop and turn, it cuts the corner) and Gaussian
 * jitter on every sample. A decoder that only handles clean input passes nothing here.
 */
class GestureDecoderTest {

    private val grid = KeyGrid.qwerty()

    /**
     * Trace [word] the way a thumb would: through its key centres, corners rounded, points every
     * [step] key units, with `sigma` of jitter. Returns (xs, ys, count) in key units.
     */
    private fun trace(
        word: String, rng: Random, sigma: Float = 0.10f, step: Float = 0.35f, round: Float = 0.25f,
    ): Triple<FloatArray, FloatArray, Int> {
        // Key centres, consecutive repeats collapsed — a finger passing "ll" only goes there once.
        val pts = ArrayList<Pair<Float, Float>>()
        var last = ' '
        for (c in word) {
            if (c == last) continue
            pts.add(grid.x(c) to grid.y(c))
            last = c
        }
        // Round the corners, so the path curves through a key rather than hitting it dead-on and
        // reversing. The cut is a CONSTANT radius, not a fraction of the leg: a thumb rounds a turn by
        // roughly the same amount however far it is travelling, and scaling the cut to the leg length
        // makes long legs miss their key by more than a whole key — a trace no one actually produces,
        // and one that made this fixture reject a decoder that was working correctly.
        val via = ArrayList<Pair<Float, Float>>()
        via.add(pts.first())
        for (i in 1 until pts.size - 1) {
            val (px, py) = pts[i - 1]
            val (cx, cy) = pts[i]
            val (nx, ny) = pts[i + 1]
            via.add(cut(cx, cy, px, py, round))
            via.add(cut(cx, cy, nx, ny, round))
        }
        if (pts.size > 1) via.add(pts.last())

        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 1 until via.size) {
            val (ax, ay) = via[i - 1]
            val (bx, by) = via[i]
            val len = Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat()
            val n = maxOf(1, (len / step).toInt())
            for (k in 0 until n) {
                val t = k.toFloat() / n
                xs.add(ax + (bx - ax) * t + (rng.nextGaussian() * sigma).toFloat())
                ys.add(ay + (by - ay) * t + (rng.nextGaussian() * sigma).toFloat())
            }
        }
        xs.add(via.last().first + (rng.nextGaussian() * sigma).toFloat())
        ys.add(via.last().second + (rng.nextGaussian() * sigma).toFloat())
        return Triple(xs.toFloatArray(), ys.toFloatArray(), xs.size)
    }

    /** A point [r] key units from ([cx],[cy]) toward ([tx],[ty]) — never past the halfway mark. */
    private fun cut(cx: Float, cy: Float, tx: Float, ty: Float, r: Float): Pair<Float, Float> {
        val len = Math.hypot((tx - cx).toDouble(), (ty - cy).toDouble()).toFloat()
        if (len < 1e-4f) return cx to cy
        val t = minOf(r / len, 0.5f)
        return cx + (tx - cx) * t to cy + (ty - cy) * t
    }

    private fun decoder(): GestureDecoder? =
        TestDictionary.bundled()?.let { GestureDecoder(it, grid) }

    private val words = listOf(
        "hello", "world", "keyboard", "phone", "light", "there", "people", "about", "would",
        "think", "great", "water", "money", "point", "under", "night", "story", "power",
    )

    /**
     * The benchmark the weights and pruning radii in [GestureDecoder] were fitted against: trace the
     * 250 commonest traceable words and measure how often the decoder's first guess is right, and how
     * often the word is anywhere in the four alternatives that backspace can reach.
     *
     * Rates, not a perfect score on a hand-picked list. Some words genuinely share a path — "hello" and
     * "help", "for" and "fir" — and no amount of tuning separates them; demanding perfection on a short
     * list just invites tuning until that list passes. The thresholds sit a little under the measured
     * numbers, so ordinary drift doesn't fail the build but a real regression does.
     */
    private fun benchmark(sigma: Float, step: Float, round: Float, seed: Long): Pair<Double, Double> {
        val dict = TestDictionary.bundled()!!
        val d = GestureDecoder(dict, grid)
        // Three letters and up, not [GestureDecoder.MIN_WORD]: the rates below have been measured
        // against this same list since the decoder was written, and folding two-letter words into it
        // would move the number for a reason that has nothing to do with a regression. They get their
        // own test.
        val common = (0 until dict.size)
            .filter { dict.isAlphaOnly(it) && dict.length(it) >= 3 }
            .sortedByDescending { dict.logFreq(it) }
            .take(250)
            .map { dict.word(it) }
        val rng = Random(seed)
        var top1 = 0
        var top4 = 0
        for (w in common) {
            val (xs, ys, n) = trace(w, rng, sigma, step, round)
            val got = d.decode(xs, ys, n, 4)
            if (got.firstOrNull() == w) top1++
            if (w in got) top4++
        }
        return 100.0 * top1 / common.size to 100.0 * top4 / common.size
    }

    @Test
    fun `decodes cleanly-drawn traces`() {
        TestDictionary.bundled() ?: return
        val (top1, top4) = benchmark(sigma = 0.06f, step = 0.35f, round = 0.25f, seed = 42)
        // Measured: 98.4% / 100%.
        assertTrue("top-1 was %.1f%%".format(top1), top1 >= 95.0)
        assertTrue("top-4 was %.1f%%".format(top4), top4 >= 99.0)
    }

    @Test
    fun `decodes traces at real typing jitter`() {
        TestDictionary.bundled() ?: return
        val (top1, top4) = benchmark(sigma = 0.22f, step = 0.55f, round = 0.38f, seed = 42)
        // Measured: 92.8% / 100%. Top-4 is the number the design leans on — the word only has to be
        // reachable by backspace, because that is how alternatives are offered without a suggestion bar.
        assertTrue("top-1 was %.1f%%".format(top1), top1 >= 88.0)
        assertTrue("top-4 was %.1f%%".format(top4), top4 >= 98.0)
    }

    @Test
    fun `keeps the word in the top four when the trace is sloppy`() {
        val d = decoder() ?: return
        val rng = Random(7)
        // Two runs per word at real-thumb jitter. The word need only survive into the alternatives,
        // since backspace cycles them — that is the contract the no-suggestion-bar design rests on.
        val missed = ArrayList<String>()
        for (w in words) {
            repeat(2) {
                val (xs, ys, n) = trace(w, rng, sigma = 0.22f, step = 0.55f, round = 0.38f)
                val got = d.decode(xs, ys, n, 4)
                if (w !in got) missed.add("$w -> $got")
            }
        }
        assertTrue("too many misses (${missed.size}/${words.size * 2}): $missed", missed.size <= 2)
    }

    @Test
    fun `survives a trace drawn small and off-centre`() {
        val d = decoder() ?: return
        val rng = Random(13)
        // The failure mode that actually matters, and the one the pruning radii were widened for: a
        // real thumb slows at each letter (so samples bunch there instead of spacing evenly), traces
        // smaller than the ideal path, and drifts. Each breaks a different assumption in the location
        // channel, and with tight pruning the right word was being discarded before it was ever scored.
        val missed = ArrayList<String>()
        for (w in words) {
            val (rx, ry, rn) = trace(w, rng, sigma = 0.16f, step = 0.45f, round = 0.32f)
            val px = ArrayList<Float>()
            val py = ArrayList<Float>()
            for (i in 0 until rn) {
                px.add(rx[i]); py.add(ry[i])
                val near = grid.nearest(rx[i], ry[i])
                if (near != null && grid.distance(near, rx[i], ry[i]) < 0.35f) {
                    repeat(3) { px.add(rx[i]); py.add(ry[i]) }   // the pause on the letter
                }
            }
            val cx = px.average().toFloat()
            val cy = py.average().toFloat()
            val xs = FloatArray(px.size) { cx + (px[it] - cx) * 0.82f + 0.30f }
            val ys = FloatArray(py.size) { cy + (py[it] - cy) * 0.82f - 0.18f }
            val got = d.decode(xs, ys, xs.size, 4)
            if (w !in got) missed.add("$w -> $got")
        }
        assertTrue("too many misses (${missed.size}/${words.size}): $missed", missed.size <= 3)
    }

    @Test
    fun `ignores a path too short to be a word`() {
        val d = decoder() ?: return
        // A tap that drifted a few tenths of a key: must decode to nothing, or ordinary typing breaks.
        val xs = floatArrayOf(4f, 4.1f, 4.15f, 4.2f)
        val ys = floatArrayOf(1f, 1.05f, 1.1f, 1.05f)
        assertEquals(emptyList<String>(), d.decode(xs, ys, xs.size, 4))
        assertEquals(emptyList<String>(), d.decode(xs, ys, 1, 4))   // fewer points than a path needs
        // Drift far enough to leave the key and it is still a tap, because both ends are on the same
        // key. This is the test the old three-letter floor was standing in for.
        for (c in listOf('f', 'w', 'a', 'o', 'k', 's')) {
            val dx = FloatArray(6) { grid.x(c) + it * 0.09f }
            val dy = FloatArray(6) { grid.y(c) }
            assertEquals("drift on $c became a word", emptyList<String>(), d.decode(dx, dy, 6, 4))
        }
    }

    /**
     * The reported bug: two-letter words never came out. Six of the twenty commonest words in English
     * are two letters long, so this is the hot path, not a corner of one.
     *
     * Both geometries are covered on purpose. "we", "as" and "ok" are strokes between neighbouring
     * keys, barely longer than a key — those used to decode to *nothing*, because the path-length floor
     * sat above them and the first letter had already been retracted. "of", "by", "is" and "so" run
     * right across the keyboard and used to decode to a three-letter word instead ("off", "buy", "its",
     * "shop"), because the dictionary scan started at length three.
     */
    @Test
    fun `decodes two-letter words`() {
        val d = decoder() ?: return
        val words = listOf(
            "of", "to", "in", "is", "it", "on", "we", "or", "by", "up", "as", "my", "be",
            "no", "so", "go", "ok", "me", "he", "us", "an", "if", "do", "at", "am", "ah",
        )
        var top1 = 0
        val missed = ArrayList<String>()
        for (w in words) {
            val (xs, ys, n) = trace(w, Random(1), sigma = 0.12f)
            val got = d.decode(xs, ys, n, 4)
            if (got.firstOrNull() == w) top1++
            if (w !in got) missed.add("$w -> $got")
        }
        // Measured: 22 of 26 first, all 26 reachable. The four that lose ("go" to "to", "ok" to "on",
        // "if" to "of", "ah" to "an") are pairs whose ideal paths genuinely coincide, the commoner word
        // wins, and backspace or the word before them settles it — the same trade the decoder has always
        // made. The four commonest of them are pinned separately below, because those must not slip.
        assertTrue("only $top1 of ${words.size} decoded first", top1 >= 22)
        assertTrue("unreachable: $missed", missed.isEmpty())
    }

    /**
     * The four the user named, pinned on their own: "to", "it", "is" and "my". They are among the
     * most-typed words in English, they are all two letters, and every one of them sits next to a word
     * the ranker would otherwise prefer — "to" to "top"/"too", "it" to "of"/"or", "is" to "its", "my"
     * to "mit". The rates in the test above would still pass with one of these broken, so they get
     * their own assertion: first guess, every seed, at ordinary jitter and as a bare two-point flick.
     */
    @Test
    fun `always decodes the commonest two-letter words first`() {
        val d = decoder() ?: return
        val pinned = listOf("to", "it", "is", "my")
        val wrong = ArrayList<String>()
        for (w in pinned) {
            for (seed in 1L..20L) {
                for (step in listOf(0.35f, 0.45f, 0.55f)) {
                    val (xs, ys, n) = trace(w, Random(seed), sigma = 0.12f, step = step)
                    val got = d.decode(xs, ys, n, 4)
                    if (got.firstOrNull() != w) wrong.add("$w seed $seed step $step -> $got")
                }
            }
            // The same stroke as the view actually reports it when the flick is quick: two points.
            val xs = floatArrayOf(grid.x(w[0]) + 0.10f, grid.x(w[1]) - 0.10f)
            val ys = floatArrayOf(grid.y(w[0]), grid.y(w[1]))
            val got = d.decode(xs, ys, 2, 4)
            if (got.firstOrNull() != w) wrong.add("two-point $w -> $got")
        }
        assertTrue("not decoded first: $wrong", wrong.isEmpty())
        // And they must survive a badly drifted stroke into the alternatives, which is all backspace
        // needs. Only that much: at 0.22 sigma a trace of "it" really does land on "of" sometimes, and
        // pretending otherwise would mean tuning the ranker against a stroke no one meant to draw.
        for (w in pinned) {
            for (seed in 1L..20L) {
                val (xs, ys, n) = trace(w, Random(seed), sigma = 0.22f, step = 0.55f, round = 0.38f)
                assertTrue("$w vanished at high jitter", w in d.decode(xs, ys, n, 4))
            }
        }
    }

    /**
     * A short flick reports very few samples — the view records the touch-down, whatever MOVEs arrive,
     * and the lift, and between two neighbouring keys that can be two points in total. The decoder has
     * to read a word out of that, because "we" and "of" are exactly the strokes that produce it.
     */
    @Test
    fun `decodes a stroke reported as only its two ends`() {
        val d = decoder() ?: return
        for (w in listOf("we", "of", "to", "by")) {
            // Both ends short of the key centre, the way a finger actually lands and lifts.
            val xs = floatArrayOf(grid.x(w[0]) + 0.10f, grid.x(w[1]) - 0.10f)
            val ys = floatArrayOf(grid.y(w[0]), grid.y(w[1]))
            val got = d.decode(xs, ys, 2, 4)
            assertEquals("two-point $w", w, got.firstOrNull())
        }
    }

    /** Three-letter words the two-letter band could plausibly have stolen. */
    @Test
    fun `still decodes the three-letter words it always did`() {
        val d = decoder() ?: return
        for (w in listOf("the", "and", "you", "for", "not", "but", "can", "how", "our", "its")) {
            val (xs, ys, n) = trace(w, Random(5), sigma = 0.12f)
            val got = d.decode(xs, ys, n, 4)
            assertTrue("$w -> $got", w in got)
        }
    }

    /**
     * Context has to reach two-letter candidates as well, and it does — which matters more here than
     * anywhere, because these are the words whose paths coincide. Nothing in the ranker is
     * length-aware; the test exists to keep it that way.
     */
    @Test
    fun `the preceding word settles a two-letter reading`() {
        val d = decoder() ?: return
        d.context = TestContext.bundled() ?: return
        val plain = decoder()!!
        for ((left, w) in listOf("can" to "go", "will" to "go", "even" to "if", "only" to "if")) {
            val (xs, ys, n) = trace(w, Random(1), sigma = 0.12f)
            val before = plain.decode(xs, ys, n, 4)
            val after = d.decode(xs, ys, n, 4, WordContext(left = left))
            assertEquals("'$left $w' was $before, ranked $after", w, after.firstOrNull())
        }
        // And it must not unseat a two-letter word that was already right.
        for ((left, w) in listOf("kind" to "of", "want" to "to", "one" to "of")) {
            val (xs, ys, n) = trace(w, Random(1), sigma = 0.12f)
            val got = d.decode(xs, ys, n, 4, WordContext(left = left))
            assertEquals("'$left $w'", w, got.firstOrNull())
        }
    }

    @Test
    fun `a slip onto the neighbouring key is not a two-letter word`() {
        val d = decoder() ?: return
        // Two different keys, so the twoKeys test passes, but only just — the finger has travelled the
        // gap between neighbours and no further, which is what an unintended drag looks like. This is
        // the reported "sometimes it'll trigger as a swipe even though I didn't mean it".
        for (pair in listOf("as", "df", "kl", "ty", "op")) {
            val from = pair[0]
            val to = pair[1]
            val xs = floatArrayOf(grid.x(from), (grid.x(from) + grid.x(to)) / 2f, grid.x(to))
            val ys = floatArrayOf(grid.y(from), (grid.y(from) + grid.y(to)) / 2f, grid.y(to))
            for (g in d.decode(xs, ys, xs.size, 4)) {
                assertTrue("a slip from $from to $to decoded to $g", g.length >= 3)
            }
        }
    }

    @Test
    fun `an uncommon two-letter word is never swiped`() {
        val d = decoder() ?: return
        val rng = Random(29)
        // In the dictionary, and so typable, but rarer than the accidental drag that would produce
        // them — deliberately unreachable by gesture.
        for (word in listOf("ax", "yo", "pa", "un", "oh", "lo")) {
            val (xs, ys, n) = trace(word, rng, sigma = 0.06f, step = 0.20f)
            assertTrue(
                "$word should not be swipeable",
                d.decode(xs, ys, n, 4).none { it == word },
            )
        }
    }

    @Test
    fun `respects the start and end keys`() {
        val d = decoder() ?: return
        val rng = Random(3)
        // Whatever else it guesses, a candidate has to begin and end where the finger did.
        for (w in listOf("hello", "keyboard", "point")) {
            val (xs, ys, n) = trace(w, rng, sigma = 0.12f)
            val got = d.decode(xs, ys, n, 4)
            assertTrue("nothing decoded for $w", got.isNotEmpty())
            for (g in got) {
                assertTrue("$g starts wrong for $w", grid.distance(g.first(), xs[0], ys[0]) < 2f)
                assertTrue(
                    "$g ends wrong for $w",
                    grid.distance(g.last(), xs[n - 1], ys[n - 1]) < 2f,
                )
            }
        }
    }

    @Test
    fun `never returns an unwriteable word`() {
        val d = decoder() ?: return
        val rng = Random(11)
        for (w in words) {
            val (xs, ys, n) = trace(w, rng, sigma = 0.15f)
            for (g in d.decode(xs, ys, n, 4)) {
                // Never a bare letter: "a" and "I" are taps, and a gesture that could return one would
                // make every drifted tap a coin toss.
                assertTrue("$g is shorter than a gesture word", g.length >= GestureDecoder.MIN_WORD)
                assertTrue("$g isn't pure a-z", g.all { it in 'a'..'z' })
            }
        }
    }

    @Test
    fun `decodes inside a touch frame`() {
        val d = decoder() ?: return
        val rng = Random(5)
        val traces = words.map { trace(it, rng, sigma = 0.12f) }
        repeat(3) { for ((xs, ys, n) in traces) d.decode(xs, ys, n, 4) }   // warm up the JIT
        val start = System.nanoTime()
        val runs = 20
        repeat(runs) { for ((xs, ys, n) in traces) d.decode(xs, ys, n, 4) }
        val perDecodeMs = (System.nanoTime() - start) / 1e6 / (runs * traces.size)
        // Decoding happens once, on finger-up. Well under a frame on a JVM leaves room for the Light
        // Phone's slower CPU; the point of the assertion is to catch the pruning being defeated.
        assertTrue("decode took %.2f ms".format(perDecodeMs), perDecodeMs < 40.0)
    }
}
