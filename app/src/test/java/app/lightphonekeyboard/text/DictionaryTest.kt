package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryTest {

    private val small = TestDictionary.of(
        "the" to 1000, "cat" to 40, "cats" to 20, "hello" to 200, "keyboard" to 30, "don't" to 90,
    )

    @Test
    fun `round trips words and lengths`() {
        assertEquals(6, small.size)
        val words = (0 until small.size).map { small.word(it) }.toSet()
        assertEquals(setOf("the", "cat", "cats", "hello", "don't", "keyboard"), words)
    }

    @Test
    fun `finds words and rejects non-words`() {
        assertTrue(small.contains("the"))
        assertTrue(small.contains("keyboard"))
        assertTrue(small.contains("don't"))
        assertFalse(small.contains("teh"))
        assertFalse(small.contains("hell"))
        assertFalse(small.contains(""))
    }

    @Test
    fun `length bands hold only words of that length`() {
        for (n in 1..small.maxLength) {
            for (i in small.lengthRange(n, n)) assertEquals(n, small.length(i))
        }
        // A band with nothing in it must come back empty rather than spilling into its neighbours.
        assertTrue(small.lengthRange(2, 2).isEmpty())
        assertEquals(2, small.lengthRange(3, 3).count())   // the, cat
    }

    @Test
    fun `frequency ordering survives the round trip`() {
        val the = small.indexOf("the")
        val cat = small.indexOf("cat")
        assertTrue("the should be commoner than cat", small.logFreq(the) > small.logFreq(cat))
    }

    @Test
    fun `masks record letters and flag apostrophes`() {
        val cat = small.indexOf("cat")
        assertEquals(Dictionary.maskOf("cat"), small.mask(cat))
        assertTrue(small.isAlphaOnly(cat))
        // An apostrophe means the word can't be traced as a gesture, which the decoder relies on.
        assertFalse(small.isAlphaOnly(small.indexOf("don't")))
        assertNotEquals(0, small.mask(small.indexOf("don't")) and Dictionary.NON_ALPHA_BIT)
    }

    @Test
    fun `bundled dictionary loads and holds the words a keyboard needs`() {
        val dict = TestDictionary.bundled() ?: return
        assertTrue("expected a substantial word list, got ${dict.size}", dict.size > 40_000)
        for (w in listOf("the", "hello", "keyboard", "phone", "light", "because", "tomorrow")) {
            assertTrue("missing $w", dict.contains(w))
        }
        // Junk must NOT be in it: a typo that is itself a dictionary word can never be corrected.
        for (w in listOf("teh", "helo", "recieve", "wrold")) {
            assertFalse("$w should not be a dictionary word", dict.contains(w))
        }
        // The loader's own invariant: every word sits in the band its length says it does.
        for (n in 1..dict.maxLength) {
            for (i in dict.lengthRange(n, n)) assertEquals(n, dict.length(i))
        }
    }
}
