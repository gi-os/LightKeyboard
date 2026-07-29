package app.lightphonekeyboard.text

import java.io.DataInputStream
import java.io.InputStream

/**
 * The bundled word list, held in flat primitive arrays.
 *
 * Why bundle a dictionary at all: the keyboard's only word knowledge used to be the phone's
 * [android.view.textservice.SpellCheckerSession], and LightOS ships without the Google spell-check
 * service, so on a real Light Phone that session answers nothing and autocorrect quietly does
 * nothing at all. Gesture (swipe) typing needs a word list outright — there is nothing to decode a
 * traced path against without one.
 *
 * Flat arrays rather than a `List<Word>`: a swipe decode touches tens of thousands of entries inside
 * one touch frame, and 80k small objects would both blow the allocation budget and scatter the data
 * across the heap. Everything here is one contiguous read.
 *
 * Words are stored sorted by length, so [lengthRange] hands the decoder exactly the band it needs
 * and it can ignore the rest of the file. Each word also carries a 26-bit [mask] of the letters it
 * uses, which prunes candidates with a single AND before any real distance work happens.
 *
 * Load off the main thread — see [Dictionary.load]. Read-only and immutable afterwards, so the IME
 * and the keyboard view can share one instance without locking.
 */
class Dictionary private constructor(
    /** Every word's characters, concatenated. Word i spans [starts[i], starts[i + 1]). */
    private val chars: CharArray,
    private val starts: IntArray,
    /** ln P(word) × 1000, as produced by tools/gen_dict.py. Higher = more common. */
    private val logf: ShortArray,
    /** Bit (c - 'a') set for each letter present. Bit 26 marks a non a-z character (an apostrophe). */
    private val mask: IntArray,
    /** lengthBand[n] = index of the first word of length n; lengthBand[maxLen + 1] = [size]. */
    private val lengthBand: IntArray,
) {
    val size: Int get() = logf.size
    val maxLength: Int get() = lengthBand.size - 2

    fun length(i: Int): Int = starts[i + 1] - starts[i]
    fun charAt(i: Int, k: Int): Char = chars[starts[i] + k]
    fun logFreq(i: Int): Float = logf[i] / 1000f
    fun mask(i: Int): Int = mask[i]

    /** True if the word is pure a-z, i.e. traceable as a gesture on the letters layer. */
    fun isAlphaOnly(i: Int): Boolean = mask[i] and NON_ALPHA_BIT == 0

    fun word(i: Int): String = String(chars, starts[i], length(i))

    /** Indices of all words whose length is in [min]..[max]. Empty when the band is out of range. */
    fun lengthRange(min: Int, max: Int): IntRange {
        val lo = min.coerceAtLeast(1)
        val hi = max.coerceAtMost(maxLength)
        if (lo > hi) return IntRange.EMPTY
        return lengthBand[lo] until lengthBand[hi + 1]
    }

    /** Index of [w], or -1. Binary search inside the word's own length band. */
    fun indexOf(w: String): Int {
        val n = w.length
        if (n < 1 || n > maxLength) return -1
        var lo = lengthBand[n]
        var hi = lengthBand[n + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareAt(mid, w)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    fun contains(w: String): Boolean = indexOf(w) >= 0

    /** Compare word [i] against [w]; both are known to be the same length here. */
    private fun compareAt(i: Int, w: String): Int {
        val base = starts[i]
        for (k in w.indices) {
            val d = chars[base + k].code - w[k].code
            if (d != 0) return d
        }
        return 0
    }

    companion object {
        const val NON_ALPHA_BIT = 1 shl 26
        private const val MAGIC = 0x314C4B44   // 'LKD1'

        /** Letter bitmask of [w]; bit 26 marks any character outside a-z. */
        fun maskOf(w: CharSequence): Int {
            var m = 0
            for (c in w) {
                val l = c.lowercaseChar()
                m = m or if (l in 'a'..'z') 1 shl (l - 'a') else NON_ALPHA_BIT
            }
            return m
        }

        /**
         * Read words.bin. Throws on a bad magic or a truncated file, so a corrupt asset surfaces as a
         * caught exception at the call site (which then falls back to the system spell checker)
         * rather than as a silently empty dictionary.
         */
        fun load(input: InputStream): Dictionary {
            val din = DataInputStream(input.buffered(1 shl 16))
            require(readIntLE(din) == MAGIC) { "words.bin: bad magic" }
            val count = readIntLE(din)
            require(count in 1..(1 shl 22)) { "words.bin: implausible word count $count" }

            val starts = IntArray(count + 1)
            val logf = ShortArray(count)
            val mask = IntArray(count)
            // Sized for the average English word (~7 chars) and grown if the file is longer.
            var chars = CharArray(count * 8)
            var pos = 0
            var maxLen = 1
            var prevLen = 0
            var sortedByLength = true

            for (i in 0 until count) {
                val len = din.readUnsignedByte()
                logf[i] = readShortLE(din)
                if (pos + len > chars.size) chars = chars.copyOf((chars.size * 2).coerceAtLeast(pos + len))
                starts[i] = pos
                var m = 0
                for (k in 0 until len) {
                    val c = din.readUnsignedByte().toChar()
                    chars[pos++] = c
                    m = m or if (c in 'a'..'z') 1 shl (c - 'a') else NON_ALPHA_BIT
                }
                mask[i] = m
                if (len < prevLen) sortedByLength = false
                prevLen = len
                if (len > maxLen) maxLen = len
            }
            starts[count] = pos
            require(sortedByLength) { "words.bin: words are not sorted by length" }

            // lengthBand[n] = first index of length n. Walk once; empty lengths collapse onto the
            // next occupied one, so lengthRange() over a gap correctly yields nothing.
            val band = IntArray(maxLen + 2)
            band[maxLen + 1] = count
            var i = count - 1
            for (n in maxLen downTo 1) {
                while (i >= 0 && starts[i + 1] - starts[i] >= n) i--
                band[n] = i + 1
            }
            return Dictionary(chars, starts, logf, mask, band)
        }

        private fun readIntLE(d: DataInputStream): Int =
            d.readUnsignedByte() or (d.readUnsignedByte() shl 8) or
                (d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 24)

        private fun readShortLE(d: DataInputStream): Short =
            (d.readUnsignedByte() or (d.readUnsignedByte() shl 8)).toShort()
    }
}
