package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reranking policy, on a hand-built table so the cases are exact.
 *
 * Half of these test that reranking *doesn't* happen. That is the point: a keyboard that reorders
 * suggestions on context is only an improvement if the candidate the spelling obviously matches is
 * still there afterwards, and if the start of a sentence — where there is no context at all — behaves
 * exactly as it did before any of this existed.
 */
class ContextRankerTest {

    private val model = TestContext.of(
        Triple("i", "think", 2.7f),
        Triple("happy", "birthday", 6.0f),
        Triple("to", "the", 1.8f),
        Triple("very", "much", 5.4f),
    )

    // --- reading the sentence ------------------------------------------------------------------

    @Test
    fun `finds the word before the one being typed`() {
        assertEquals("i", ContextRanker.leftContextOf("i thin"))
        assertEquals("happy", ContextRanker.leftContextOf("happy birth"))
        assertEquals("very", ContextRanker.leftContextOf("it was very "))
        assertEquals("case should not matter", "very", ContextRanker.leftContextOf("It was Very much"))
    }

    @Test
    fun `has no context at the start of a sentence`() {
        // The first word of every message goes through here, so this is the common path, not an edge.
        assertNull(ContextRanker.leftContextOf(null))
        assertNull(ContextRanker.leftContextOf(""))
        assertNull("nothing before it", ContextRanker.leftContextOf("thin"))
        assertNull("after a full stop", ContextRanker.leftContextOf("Long day. thin"))
        assertNull("after a question mark", ContextRanker.leftContextOf("Really? thin"))
        assertNull("after an exclamation", ContextRanker.leftContextOf("Yes! thin"))
        assertNull("after a newline", ContextRanker.leftContextOf("Dear Sam\nthin"))
        assertNull("nothing but punctuation", ContextRanker.leftContextOf(". "))
    }

    @Test
    fun `steps over punctuation that doesn't end the sentence`() {
        // A comma interrupts a sentence without ending it, and "well, i" really is the pair "well i".
        assertEquals("well", ContextRanker.leftContextOf("well, i"))
        assertEquals("i", ContextRanker.leftContextOf("(i "))
        assertEquals("don't", ContextRanker.leftContextOf("i don't thin"))
    }

    // --- reordering ---------------------------------------------------------------------------

    @Test
    fun `promotes the word that fits the sentence`() {
        // The complaint this exists for: "thing" is the commoner word, so it arrives first, and "i thing"
        // is not English.
        val got = ContextRanker.rerank(listOf("thing", "think", "thin"), "i", model, 3)
        assertEquals("think", got.first())
    }

    @Test
    fun `changes nothing at the start of a sentence`() {
        val candidates = listOf("thing", "think", "thin")
        assertEquals(candidates, ContextRanker.rerank(candidates, null, model, 3))
    }

    @Test
    fun `changes nothing without a table`() {
        // The soft-failure path: a missing or corrupt asset must rank exactly as the keyboard did before
        // the table existed, not throw and not empty the strip.
        val candidates = listOf("thing", "think", "thin")
        assertEquals(candidates, ContextRanker.rerank(candidates, "i", null, 3))
    }

    @Test
    fun `keeps the obvious candidate reachable`() {
        // Every one of these has a maximal pair with "happy" and would otherwise sweep past "birthda"'s
        // only real completion. Dropping what the user is plainly typing is a worse bug than showing a
        // word that doesn't fit the sentence, so it goes back in the last slot.
        val candidates = listOf("birthdays", "birthday", "birthday's")
        val got = ContextRanker.rerank(candidates, "happy", model, 2)
        assertEquals(2, got.size)
        assertTrue("lost the base-best candidate: $got", "birthdays" in got)
        assertEquals("birthday", got.first())
    }

    @Test
    fun `cannot move a candidate further than the cap allows`() {
        // A maximal pair at position 3 or beyond can't reach the front: the cap is three places and the
        // sort is stable, so a tie leaves the incoming order alone. This is what bounds the damage a
        // fingerprint collision can do.
        val candidates = listOf("a", "b", "c", "d", "much", "e")
        val got = ContextRanker.rerank(candidates, "very", model, 6)
        assertEquals("a", got.first())
        assertTrue("much should still have climbed: $got", got.indexOf("much") < 4)
    }

    @Test
    fun `never invents or drops a candidate`() {
        val candidates = listOf("thing", "think", "thin", "thins")
        val got = ContextRanker.rerank(candidates, "i", model, 4)
        assertEquals(candidates.sorted(), got.sorted())
    }

    @Test
    fun `a correction moves less than a suggestion`() {
        // Autocorrect commits without asking, so the same pair is allowed to do less there. "very much"
        // scores 5.4 nats and would climb 2.7 places in the strip, which reaches the front from third —
        // but the correction bound stops at 1.2, so from third it cannot get there at all.
        val candidates = listOf("mush", "mulch", "much")
        assertEquals(
            listOf("much", "mush", "mulch"),
            ContextRanker.rerank(candidates, "very", model, 3, ContextRanker.MAX_SHIFT),
        )
        // Under the correction bound it may still climb — just never to the front from third, which is
        // the position that decides what gets committed.
        assertEquals(
            "mush",
            ContextRanker.rerank(candidates, "very", model, 3, ContextRanker.MAX_SHIFT_CORRECTION).first(),
        )
    }

    @Test
    fun `a correction still promotes the runner-up on a real collocation`() {
        // The bound is tight, not off: from second place a pair above two nats does overtake, which is
        // the "i thinl" -> think case rather than thing.
        assertEquals(
            listOf("think", "thing"),
            ContextRanker.rerank(listOf("thing", "think"), "i", model, 2, ContextRanker.MAX_SHIFT_CORRECTION),
        )
    }

    @Test
    fun `handles the degenerate asks`() {
        assertEquals(emptyList<String>(), ContextRanker.rerank(listOf("a", "b"), "i", model, 0))
        assertEquals(listOf("a"), ContextRanker.rerank(listOf("a"), "i", model, 3))
        assertEquals(emptyList<String>(), ContextRanker.rerank(emptyList(), "i", model, 3))
    }
}
