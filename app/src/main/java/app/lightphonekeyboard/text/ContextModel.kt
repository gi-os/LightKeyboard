package app.lightphonekeyboard.text

import java.io.DataInputStream
import java.io.InputStream

/**
 * How strongly one word follows another — the bundled word-pair table.
 *
 * Everything else in here judges a candidate on the current word alone: how close it is to what was
 * typed, and how common it is in English. That is why the strip offers nonsense. Typing "i thin" puts
 * "thing" above "think" because "thing" is the commoner word, and "i thing" is not a sentence anyone
 * has ever written. This table is the smallest thing that answers that, and it answers only that — it
 * is not a language model, it cannot see further than one word back, and it is used to *reorder*
 * candidates the existing matchers already produced, never to invent or veto one.
 *
 * What is stored is pointwise mutual information, `ln( P(l,r) / (P(l)*P(r)) )`, not the pair's
 * probability. The rankers already have `ln P(r)` from words.bin; what they lack is whether `r`
 * follows `l` more often than chance would explain, which is exactly PMI. Storing the conditional
 * probability instead would mean counting the unigram prior twice.
 *
 * The file is an open-addressed hash table with no word list in it at all — the key is a fingerprint
 * of the pair, so there is no vocabulary to parse and no string map to hold. A lookup is a hash, a
 * mask and one or two array reads, which is what makes this affordable on every keystroke inside an
 * IME. The price of having no vocabulary is that two different pairs can share a fingerprint; at 32
 * bits with 180k entries that is roughly a 1-in-24,000 chance per lookup of a word getting a small
 * boost it did not earn, and [ContextRanker] can only move a candidate a couple of places, so it
 * cannot turn that into a wrong word. See tools/gen_bigrams.py for the format and the sources.
 *
 * Load off the main thread — the arrays are 1.25 MB. Read-only and immutable afterwards, so the IME
 * and the keyboard view can share one instance without locking.
 */
class ContextModel private constructor(
    /** Pair fingerprints; 0 marks an empty slot. Length is a power of two. */
    private val keys: IntArray,
    /** Quantised PMI per slot, `pmi / PMI_FULL * 255`. */
    private val scores: ByteArray,
    /** Live entries. Reported for sanity in tests; never touched by a lookup. */
    val size: Int,
) {
    private val mask = keys.size - 1

    /**
     * PMI of the pair ([left], [right]) in nats, or 0 when the table has never seen it.
     *
     * 0 is the right answer for "never seen" as well as for "no signal": both mean rank this candidate
     * exactly as it would have been ranked before there was a context model, which is the safety
     * property that makes reranking preferable to filtering.
     *
     * Both words must already be lowercase. Callers have them that way anyway, and lowercasing here
     * would allocate a String on every keystroke for nothing.
     */
    fun pmi(left: String, right: String): Float {
        if (left.isEmpty() || right.isEmpty()) return 0f
        val h = fingerprint(left, right)
        var i = h and mask
        // Linear probing. The table is built at a 0.69 load factor, so a miss walks about two slots.
        while (true) {
            val k = keys[i]
            if (k == 0) return 0f
            if (k == h) return (scores[i].toInt() and 0xFF) * PMI_FULL / 255f
            i = (i + 1) and mask
        }
    }

    companion object {
        private const val MAGIC = 0x314C4B42   // 'LKB1'

        /** The cap the generator quantises against. Changing it here alone would rescale every score. */
        const val PMI_FULL = 10.0f

        /**
         * FNV-1a over `left`, a zero byte, then `right`. Mirrored exactly by `fingerprint()` in
         * tools/gen_bigrams.py. If the two ever drift apart every lookup misses and the feature
         * silently does nothing instead of failing loudly, which is why [ContextModelTest] asserts
         * against known-good pairs read out of the shipped file.
         */
        fun fingerprint(left: String, right: String): Int {
            var h = -0x7EE3623B                       // 0x811C9DC5, FNV-1a's offset basis
            for (c in left) h = (h xor (c.code and 0xFF)) * PRIME
            h *= PRIME                                // the separating zero byte: xor 0 is a no-op
            for (c in right) h = (h xor (c.code and 0xFF)) * PRIME
            return if (h == 0) 1 else h               // 0 means "empty slot", so it can't mean a pair
        }

        private const val PRIME = 0x01000193

        /**
         * Read bigrams.bin. Throws on bad magic, an implausible capacity or a truncated file, so a
         * corrupt asset surfaces as an exception at the call site — which carries on without context
         * ranking rather than failing. An IME that throws is no keyboard at all, in every app at once.
         */
        fun load(input: InputStream): ContextModel {
            val din = DataInputStream(input.buffered(1 shl 16))
            require(readIntLE(din) == MAGIC) { "bigrams.bin: bad magic" }
            val capacity = readIntLE(din)
            require(capacity in 2..(1 shl 22) && capacity and (capacity - 1) == 0) {
                "bigrams.bin: capacity $capacity is not a plausible power of two"
            }
            val count = readIntLE(din)
            require(count in 0..capacity) { "bigrams.bin: $count entries in $capacity slots" }
            val keys = IntArray(capacity)
            for (i in 0 until capacity) keys[i] = readIntLE(din)
            val scores = ByteArray(capacity)
            din.readFully(scores)
            return ContextModel(keys, scores, count)
        }

        private fun readIntLE(d: DataInputStream): Int =
            d.readUnsignedByte() or (d.readUnsignedByte() shl 8) or
                (d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 24)
    }
}
