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

    // --- the keep-as-typed slot (iOS's "word" slot) -------------------------------------------

    @Test
    fun `offers the literal for a word the dictionary has never heard of`() {
        val s = suggester() ?: return
        // Names are the case this exists for: absent from SCOWL and sitting next to a common word, so
        // autocorrect would otherwise eat them every time.
        for (p in listOf("Lupo", "Aoife", "Bjorn", "Ravindra")) {
            assertEquals("no literal offered for $p", p, s.literalFor(p))
        }
    }

    @Test
    fun `does not offer the literal for a word it already knows`() {
        val s = suggester() ?: return
        // Nothing to learn and nothing about to be corrected, so quoting it would just look like the
        // strip doubting a correctly spelled word.
        for (p in listOf("the", "keyboard", "receive", "basil")) {
            assertEquals("literal offered for known word $p", null, s.literalFor(p))
        }
    }

    @Test
    fun `does not offer the literal while a word is still being typed`() {
        val s = suggester() ?: return
        // The one that keeps the strip quiet: every unfinished word is "unknown", so without the
        // going-nowhere test, typing "keyboard" would offer to add "k", "ke", "key" and "keyb".
        for (p in listOf("ke", "key", "keyb", "keyboa", "tomo", "becau", "lup", "recei")) {
            assertEquals("literal offered mid-word for $p", null, s.literalFor(p))
        }
    }

    @Test
    fun `offers the literal as soon as the prefix stops going anywhere`() {
        val s = suggester() ?: return
        // "lup" still reaches lupus/lupine, so nothing; "lupo" reaches nothing, so the slot appears.
        assertEquals(null, s.literalFor("Lup"))
        assertEquals("Lupo", s.literalFor("Lupo"))
    }

    @Test
    fun `does not offer a literal the personal list would refuse`() {
        val s = suggester() ?: return
        // UserWords rejects these, so a slot that claimed to add them would silently do nothing.
        for (p in listOf("", "a", "mp3", "don't-", "x9")) {
            assertEquals("literal offered for unacceptable $p", null, s.literalFor(p))
        }
    }

    @Test
    fun `does not re-offer a literal already in the personal list`() {
        val s = suggester() ?: return
        s.userWords = UserWords.of(listOf("Lupo"))
        assertEquals(null, s.literalFor("Lupo"))
        assertEquals("case should not matter", null, s.literalFor("lupo"))
        s.userWords = UserWords.EMPTY
    }

    @Test
    fun `strip puts the literal first and still fills the remaining slots`() {
        val s = suggester() ?: return
        val strip = s.stripFor("Bjorn", Suggester.SLOTS)
        assertTrue("strip was empty", strip.isNotEmpty())
        assertEquals("Bjorn", strip.first().word)
        assertTrue("first slot not marked literal", strip.first().literal)
        assertTrue("too many slots: $strip", strip.size <= Suggester.SLOTS)
        assertTrue("later slots marked literal: $strip", strip.drop(1).none { it.literal })
    }

    @Test
    fun `strip is all suggestions while a word is still being typed`() {
        val s = suggester() ?: return
        // "keyb" is not a word, but it is going somewhere, so the slots are completions only — this is
        // the common case while typing and the strip should look no different than before.
        val strip = s.stripFor("keyb", Suggester.SLOTS)
        assertTrue("no suggestions for keyb", strip.isNotEmpty())
        assertTrue("unexpected literal in $strip", strip.none { it.literal })
        assertTrue("keyboard missing from $strip", strip.any { it.word == "keyboard" })
    }

    @Test
    fun `strip never repeats a word across slots`() {
        val s = suggester() ?: return
        for (p in listOf("Bjorn", "keyb", "recieve", "Lupo", "th", "a")) {
            val words = s.stripFor(p, Suggester.SLOTS).map { it.word.lowercase() }
            assertEquals("duplicates for $p: $words", words.distinct().size, words.size)
        }
    }
}
