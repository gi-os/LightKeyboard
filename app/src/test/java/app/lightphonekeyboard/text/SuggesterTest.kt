package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggesterTest {

    private fun suggester(): Suggester? = TestDictionary.bundled()?.let {
        Suggester(it, Corrector(it, KeyGrid.qwerty()))
    }

    /** The same, with the shipped word-pair table wired in. Null if either asset is missing. */
    private fun contextual(): Suggester? {
        val pairs = TestContext.bundled() ?: return null
        val dict = TestDictionary.bundled() ?: return null
        val c = Corrector(dict, KeyGrid.qwerty())
        c.context = pairs
        return Suggester(dict, c).apply { context = pairs }
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

    // --- ranking against the word before ------------------------------------------------------

    @Test
    fun `puts the completion that fits the sentence first`() {
        val s = contextual() ?: return
        // Real cases, each one a word the frequency prior gets wrong and the preceding word gets right.
        // "happy bir" offered "birth"; "went wro" offered "wrote"; "how mu" offered "music". None of
        // them is a bad guess about the *word* — they are all bad guesses about the sentence.
        val cases = listOf(
            Triple("happy", "bir", "birthday"),
            Triple("went", "wro", "wrong"),
            Triple("how", "mu", "much"),
            Triple("let", "alo", "alone"),
            Triple("good", "lu", "luck"),
            Triple("take", "adva", "advantage"),
            Triple("very", "plea", "pleased"),
            Triple("my", "boy", "boyfriend"),
        )
        val wrong = cases.mapNotNull { (left, prefix, want) ->
            val got = s.forPrefix(prefix, Suggester.SLOTS, WordContext(left))
            if (got.firstOrNull() == want) null else "$left $prefix -> $got (wanted $want)"
        }
        assertTrue("context ranking missed: $wrong", wrong.isEmpty())
        // ...and every one of them is a case the current word alone gets wrong, which is what makes them
        // worth asserting. If the frequency order ever starts agreeing, these stop testing anything.
        val alreadyRight = cases.filter { (_, prefix, want) ->
            s.forPrefix(prefix, Suggester.SLOTS).firstOrNull() == want
        }
        assertTrue("no longer demonstrates anything: $alreadyRight", alreadyRight.isEmpty())
    }

    @Test
    fun `ranks a sentence start exactly as it always did`() {
        val s = contextual() ?: return
        // There is no left context for the first word of a message and no table entry for one either, so
        // this path has to be untouched — it is the one every message goes through.
        for (p in listOf("thin", "wor", "keyb", "tomo", "s")) {
            assertEquals(
                "sentence start changed for $p",
                s.forPrefix(p, Suggester.SLOTS),
                s.forPrefix(p, Suggester.SLOTS, WordContext(null)),
            )
        }
    }

    @Test
    fun `never hides the word being typed, whatever the sentence`() {
        val s = contextual() ?: return
        // The rule that makes this safe to ship. Whatever the preceding word does to the order, the
        // completion the spelling liked best has to still be somewhere in the strip: a keyboard that
        // buries the obvious word is worse than one that occasionally offers a silly one.
        val lefts = listOf("i", "the", "to", "very", "happy", "on", "a", "good", "went")
        for (p in listOf("thin", "birthda", "muc", "morn", "wa", "lo", "keyb", "peo", "tomo")) {
            val obvious = s.forPrefix(p, Suggester.SLOTS).firstOrNull() ?: continue
            for (left in lefts) {
                val got = s.forPrefix(p, Suggester.SLOTS, WordContext(left))
                assertTrue("$left $p lost $obvious: $got", obvious in got)
            }
        }
    }

    @Test
    fun `keeps the strip the same size and duplicate-free with context`() {
        val s = contextual() ?: return
        for (left in listOf("i", "very", "happy", null)) {
            for (p in listOf("thin", "birthda", "muc", "a", "th", "Wil")) {
                val got = s.stripFor(p, Suggester.SLOTS, WordContext(left))
                assertTrue("$left $p gave ${got.size}", got.size <= Suggester.SLOTS)
                val words = got.map { it.word.lowercase() }
                assertEquals("duplicates for $left $p: $words", words.distinct().size, words.size)
            }
        }
    }

    // --- keeping a word that never stops having completions -----------------------------------

    @Test
    fun `offers the literal for a name that is also the start of a real word`() {
        val s = suggester() ?: return
        // The gap this closes. "Sam" completes to "same" and "sample" forever, so the going-nowhere rule
        // never fired and the only way to add the name was the settings screen. A capital typed in the
        // middle of a sentence is the signal, and the IME is what decides that; here it arrives settled.
        // All five are absent from the word list and all five complete forever: Wil to Wilbur, Kai to
        // Kaiser, Cass to Casserole, Isa to Isabel, Bram to Bramble.
        for (p in listOf("Wil", "Kai", "Cass", "Isa", "Bram")) {
            assertEquals("no literal for $p", p, s.literalFor(p, WordContext("called", settled = true)))
        }
    }

    @Test
    fun `still says nothing mid-word when the capital is the keyboard's own doing`() {
        val s = suggester() ?: return
        // Auto-capitalisation puts a capital on the first word of every sentence, so a capital there says
        // nothing at all — and the strip must not start offering to learn "K", "Ke", "Key".
        for (p in listOf("Ke", "Key", "Keyb", "Wil", "Cass", "Tomo")) {
            assertEquals("literal offered for $p", null, s.literalFor(p))
        }
    }

    @Test
    fun `never offers a literal for a word it already knows, settled or not`() {
        val s = suggester() ?: return
        // Being settled bypasses the completions test, not the is-this-already-a-word test.
        for (p in listOf("the", "keyboard", "receive", "basil", "same")) {
            assertEquals("literal offered for $p", null, s.literalFor(p, WordContext(settled = true)))
        }
    }

    @Test
    fun `a settled literal still has to be storable`() {
        val s = suggester() ?: return
        for (p in listOf("", "a", "mp3", "don't-", "x9")) {
            assertEquals("literal offered for $p", null, s.literalFor(p, WordContext(settled = true)))
        }
    }
}
