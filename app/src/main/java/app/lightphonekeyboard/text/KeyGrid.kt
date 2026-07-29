package app.lightphonekeyboard.text

import kotlin.math.sqrt

/**
 * Where the a-z keys sit, in *key units* — x divided by key width, y divided by row pitch. The view
 * builds one of these from its real laid-out key rects, so both the corrector and the gesture decoder
 * work off the actual on-screen geometry rather than a hard-coded QWERTY picture. That is what lets
 * AZERTY and QWERTZ, and the three height presets, all behave correctly with no extra tables.
 *
 * Key units (not pixels) so the numbers are comparable across the height presets, and so the tuning
 * constants in [Corrector] and [GestureDecoder] mean "a fraction of a key" everywhere.
 *
 * Pure data — no Android types — so the typing logic stays unit-testable on the JVM.
 */
class KeyGrid(
    /** x per letter, indexed 0..25 for a..z. NaN marks a letter absent from this layout. */
    private val xs: FloatArray,
    private val ys: FloatArray,
) {
    init {
        require(xs.size == LETTERS && ys.size == LETTERS) { "KeyGrid needs 26 positions" }
    }

    fun has(c: Char): Boolean {
        val i = index(c)
        return i >= 0 && !xs[i].isNaN()
    }

    fun x(c: Char): Float = xs[index(c)]
    fun y(c: Char): Float = ys[index(c)]

    /** Straight-line distance between two keys, in key units. [Float.MAX_VALUE] if either is absent. */
    fun distance(a: Char, b: Char): Float {
        val i = index(a)
        val j = index(b)
        if (i < 0 || j < 0 || xs[i].isNaN() || xs[j].isNaN()) return Float.MAX_VALUE
        val dx = xs[i] - xs[j]
        val dy = ys[i] - ys[j]
        return sqrt(dx * dx + dy * dy)
    }

    /** Distance from an arbitrary point to a key, in key units. */
    fun distance(c: Char, px: Float, py: Float): Float {
        val i = index(c)
        if (i < 0 || xs[i].isNaN()) return Float.MAX_VALUE
        val dx = xs[i] - px
        val dy = ys[i] - py
        return sqrt(dx * dx + dy * dy)
    }

    /** Bitmask of every letter within [radius] key units of ([px], [py]). */
    fun lettersNear(px: Float, py: Float, radius: Float): Int {
        var m = 0
        val r2 = radius * radius
        for (i in 0 until LETTERS) {
            if (xs[i].isNaN()) continue
            val dx = xs[i] - px
            val dy = ys[i] - py
            if (dx * dx + dy * dy <= r2) m = m or (1 shl i)
        }
        return m
    }

    /** The nearest letter to a point, or null if the grid is empty. */
    fun nearest(px: Float, py: Float): Char? {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until LETTERS) {
            if (xs[i].isNaN()) continue
            val dx = xs[i] - px
            val dy = ys[i] - py
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best < 0) null else ('a' + best)
    }

    private fun index(c: Char): Int {
        val l = c.lowercaseChar()
        return if (l in 'a'..'z') l - 'a' else -1
    }

    companion object {
        const val LETTERS = 26

        /** Build from (letter, x, y) triples already expressed in key units. */
        fun of(positions: List<Triple<Char, Float, Float>>): KeyGrid {
            val xs = FloatArray(LETTERS) { Float.NaN }
            val ys = FloatArray(LETTERS) { Float.NaN }
            for ((c, x, y) in positions) {
                val l = c.lowercaseChar()
                if (l in 'a'..'z') { xs[l - 'a'] = x; ys[l - 'a'] = y }
            }
            return KeyGrid(xs, ys)
        }

        /**
         * A nominal QWERTY grid: one key unit per column, one row pitch per row, with the usual
         * half-key stagger. Used by tests and as the fallback before the view has been laid out.
         */
        fun qwerty(): KeyGrid = fromRows(
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            listOf(0f, 0.5f, 1.5f),
        )

        fun fromRows(rows: List<String>, indents: List<Float>): KeyGrid {
            val out = ArrayList<Triple<Char, Float, Float>>()
            rows.forEachIndexed { r, row ->
                val indent = indents.getOrElse(r) { 0f }
                row.forEachIndexed { c, ch -> out.add(Triple(ch, indent + c, r.toFloat())) }
            }
            return of(out)
        }
    }
}
