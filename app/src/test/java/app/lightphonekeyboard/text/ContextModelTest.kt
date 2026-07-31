package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word-pair table: that the shipped file parses, that its scores are the ones the generator meant,
 * and that a lookup is cheap enough to do on every keystroke.
 *
 * The pairs asserted below are the load-bearing part. There is no vocabulary in the file — the key is a
 * hash computed independently in Python and in Kotlin — so if those two ever drift apart every lookup
 * returns 0 and context ranking silently switches itself off. Nothing else would notice.
 */
class ContextModelTest {

    @Test
    fun `reads the shipped table`() {
        val m = TestContext.bundled() ?: return
        assertTrue("implausible table size ${m.size}", m.size in 100_000..1_000_000)
    }

    @Test
    fun `agrees with the generator about where a pair lives`() {
        val m = TestContext.bundled() ?: return
        // Everyday pairs, all present in Norvig's bigram counts with a positive PMI. A zero here is the
        // signature of the Kotlin and Python fingerprints having diverged.
        for ((left, right) in listOf(
            "thank" to "you", "happy" to "birthday", "went" to "to", "good" to "morning",
            "a" to "lot", "very" to "much", "my" to "way", "on" to "my", "i" to "think",
        )) {
            assertTrue("$left $right scored 0", m.pmi(left, right) > 0f)
        }
    }

    @Test
    fun `says nothing about a pair nobody writes`() {
        val m = TestContext.bundled() ?: return
        // "i thing" is the case this whole feature exists for: both words are common, the pair is not
        // English, and a unigram-only ranker cannot tell.
        for ((left, right) in listOf(
            "i" to "thing", "the" to "keyboards", "zzzz" to "qqqq", "birthday" to "happy",
        )) {
            assertEquals("$left $right should be unknown", 0f, m.pmi(left, right), 0f)
        }
    }

    @Test
    fun `a fixed phrase outscores an ordinary one`() {
        val m = TestContext.bundled() ?: return
        assertTrue(
            "happy birthday ${m.pmi("happy", "birthday")} should beat to the ${m.pmi("to", "the")}",
            m.pmi("happy", "birthday") > m.pmi("to", "the"),
        )
    }

    @Test
    fun `round-trips a hand-built table`() {
        val m = TestContext.of(
            Triple("happy", "birthday", 6.0f),
            Triple("i", "think", 2.7f),
        )
        assertEquals(6.0f, m.pmi("happy", "birthday"), 0.05f)
        assertEquals(2.7f, m.pmi("i", "think"), 0.05f)
        assertEquals(0f, m.pmi("i", "thing"), 0f)
        assertEquals("an empty word can't be a key", 0f, m.pmi("", "think"), 0f)
    }

    @Test
    fun `looks up fast enough to run on every keystroke`() {
        val m = TestContext.bundled() ?: return
        val lefts = listOf("i", "the", "to", "on", "my", "very", "happy", "went", "a", "good")
        val rights = listOf("think", "thing", "way", "much", "birthday", "morning", "lot", "to")
        repeat(50) { for (l in lefts) for (r in rights) m.pmi(l, r) }
        val start = System.nanoTime()
        val runs = 2000
        repeat(runs) { for (l in lefts) for (r in rights) m.pmi(l, r) }
        val perLookupNs = (System.nanoTime() - start).toDouble() / (runs * lefts.size * rights.size)
        // Measures about 10 ns warm, which is what makes reranking affordable: a keystroke does a couple
        // of dozen of these and the whole pass costs ~12 us. The ceiling is left two orders of magnitude
        // above that because CI hardware varies wildly; what it is really there to catch is the open
        // addressing degenerating, which is what a load factor raised past 0.7 would do.
        assertTrue("a lookup took %.0f ns".format(perLookupNs), perLookupNs < 1000.0)
    }
}
