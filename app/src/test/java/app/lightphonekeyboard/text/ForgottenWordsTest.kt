package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The forgotten list — words held down in the suggestion strip to be rid of.
 *
 * The important assertion is the last one: forgetting a word must not make it misspelled. Typing it
 * deliberately has to still be left alone, or the gesture turns "stop offering me this" into "rewrite
 * this every time I type it", which is the opposite of what was asked for.
 */
class ForgottenWordsTest {

    @Test
    fun `holds words case-insensitively and round-trips through prefs`() {
        val f = ForgottenWords.EMPTY.withWord("Ducking").withWord("brb")
        assertTrue(f.contains("ducking"))
        assertTrue(f.contains("DUCKING"))
        assertTrue(f.contains("brb"))
        assertFalse(f.contains("duck"))
        assertEquals(f.entries, ForgottenWords.deserialize(f.serialize()).entries)
    }

    @Test
    fun `refuses what the personal list would refuse`() {
        // One rule for both lists, so neither can hold something the other would reject.
        for (bad in listOf("", " ", "a", "mp3", "don't-it", "x9")) {
            assertTrue("accepted $bad", ForgottenWords.EMPTY.withWord(bad).isEmpty)
        }
    }

    @Test
    fun `ignores a duplicate and forgets nothing twice`() {
        val f = ForgottenWords.EMPTY.withWord("Wendy").withWord("wendy")
        assertEquals(1, f.size)
        assertTrue(f.without("WENDY").isEmpty)
    }

    @Test
    fun `stops the strip offering the word`() {
        val dict = TestDictionary.bundled() ?: return
        val c = Corrector(dict, KeyGrid.qwerty())
        val s = Suggester(dict, c)
        assertTrue("ducking missing to begin with", "ducking" in s.forPrefix("duckin", 5))
        // Both, the way TextEngine wires them: a word can reach the strip as a completion or as a
        // repair, and the two are found by different objects holding the list separately.
        val gone = ForgottenWords.EMPTY.withWord("ducking")
        s.forgotten = gone
        c.forgotten = gone
        assertFalse("still offered: ${s.forPrefix("duckin", 5)}", "ducking" in s.forPrefix("duckin", 5))
    }

    @Test
    fun `stops autocorrect arriving at the word but still leaves it alone when typed`() {
        val dict = TestDictionary.bundled() ?: return
        val c = Corrector(dict, KeyGrid.qwerty())
        assertEquals("receive", c.correct("recieve"))
        c.forgotten = ForgottenWords.EMPTY.withWord("receive")
        assertTrue("still corrected to it", c.correct("recieve") != "receive")
        // ...and the word itself is still a word. Forgetting says "stop offering me this", never "this
        // is a misspelling" — otherwise typing it deliberately would get it rewritten.
        assertEquals(null, c.correct("receive"))
    }
}
