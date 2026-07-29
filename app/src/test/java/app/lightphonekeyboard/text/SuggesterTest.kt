package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggesterTest {

    private fun suggester(): Suggester? = TestDictionary.bundled()?.let {
        Suggester(it, Corrector(it, KeyGrid.qwerty()))
    }

    @Test
    fun `completes a partial word`() {
        val s = suggester() ?: return
        val cases = mapOf(
            "keyb" to "keyboard",
            "tomo" to "tomorrow",
            "becau" to "because",
            "peo" to "people",
        )
        val wrong = cases.filterNot { (prefix, want) -> want in s.forPrefix(prefix) }
            .map { (prefix, want) -> "$prefix -> ${s.forPrefix(prefix)} (wanted $want)" }
        assertTrue("completions missing: $wrong", wrong.isEmpty())
    }

    @Test
    fun `never offers the prefix back`() {
        val s = suggester() ?: return
        // "the" is a word; suggesting "the" while the cursor sits after "the" wastes a slot.
        for (p in listOf("the", "and", "keyboard", "phone")) {
            assertTrue("$p suggested itself", s.forPrefix(p).none { it.equals(p, ignoreCase = true) })
        }
    }

    @Test
    fun `offers repairs for a prefix that is going nowhere`() {
        val s = suggester() ?: return
        // "recieve" completes to nothing, so the slots should carry the correction instead.
        assertTrue("expected receive in ${s.forPrefix("recieve")}", "receive" in s.forPrefix("recieve"))
    }

    @Test
    fun `stays quiet when there is nothing to say`() {
        val s = suggester() ?: return
        assertEquals(emptyList<String>(), s.forPrefix(""))
        assertEquals(emptyList<String>(), s.forPrefix("mp3"))
        assertEquals(emptyList<String>(), s.forPrefix("zzzzzz"))
    }

    @Test
    fun `fills at most the slots it is given, without duplicates`() {
        val s = suggester() ?: return
        for (p in listOf("a", "th", "he", "wor", "keyb", "s")) {
            val got = s.forPrefix(p, Suggester.SLOTS)
            assertTrue("$p gave ${got.size}", got.size <= Suggester.SLOTS)
            assertEquals("duplicates for $p: $got", got.map { it.lowercase() }.distinct().size, got.size)
        }
    }

    @Test
    fun `completes fast enough to run on every keystroke`() {
        val s = suggester() ?: return
        val prefixes = listOf("k", "ke", "key", "keyb", "t", "th", "the", "b", "be", "bec", "s", "wor")
        repeat(20) { for (p in prefixes) s.forPrefix(p) }
        val start = System.nanoTime()
        val runs = 200
        repeat(runs) { for (p in prefixes) s.forPrefix(p) }
        val perKeystrokeMs = (System.nanoTime() - start) / 1e6 / (runs * prefixes.size)
        // This runs on every letter typed, so it has a fraction of a keystroke, not a whole one. The
        // prefix search is bounded by the number of completions rather than the dictionary size — this
        // assertion is what catches that guarantee being lost.
        assertTrue("completion took %.2f ms".format(perKeystrokeMs), perKeystrokeMs < 8.0)
    }
}
