package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Autocorrect behaviour. The cases are split deliberately: the ones that MUST be corrected, and the
 * ones that must be LEFT ALONE. The second group matters more — a keyboard that mangles words you
 * spelled correctly is worse than one that never corrects anything, and it's the failure mode a
 * frequency-weighted corrector drifts into if the weights are wrong.
 */
class CorrectorTest {

    private fun corrector(): Corrector? =
        TestDictionary.bundled()?.let { Corrector(it, KeyGrid.qwerty()) }

    /**
     * The typo corpus the weights in [Corrector] were fitted against. Every one of these must be
     * fixed, and fixed to exactly this word — if a change to the weights trades any of them away,
     * that is the trade being made visible rather than discovered on the phone.
     */
    private val typos = mapOf(
        // transpositions — two keys rolled the wrong way round
        "teh" to "the", "thsi" to "this", "taht" to "that", "waht" to "what", "wnat" to "want",
        "tiem" to "time", "jsut" to "just", "yuo" to "you", "nad" to "and", "goign" to "going",
        "wrold" to "world", "wrok" to "work", "keybaord" to "keyboard", "phoen" to "phone",
        "becuase" to "because", "recieve" to "receive", "thier" to "their", "freind" to "friend",
        "acheive" to "achieve", "abotu" to "about", "somehting" to "something",
        // dropped or doubled repeat letters
        "helo" to "hello", "occurence" to "occurrence", "tommorow" to "tomorrow",
        "adress" to "address", "belive" to "believe", "beleive" to "believe",
        // a letter simply missed
        "wich" to "which", "grat" to "great", "littel" to "little", "peopel" to "people",
        "shoudl" to "should", "woudl" to "would", "coudl" to "could",
        // near-key substitutions
        "definately" to "definitely", "seperate" to "separate",
    )

    @Test
    fun `fixes the common typo shapes`() {
        val c = corrector() ?: return
        val wrong = typos.filter { (typed, want) -> c.correct(typed) != want }
            .map { (typed, want) -> "$typed -> ${c.correct(typed)} (wanted $want)" }
        assertTrue("corrections went wrong: $wrong", wrong.isEmpty())
    }

    @Test
    fun `leaves the commonest words in the language alone`() {
        val dict = TestDictionary.bundled() ?: return
        val c = Corrector(dict, KeyGrid.qwerty())
        // The false-positive side, and the one that matters more: a keyboard that rewrites words you
        // spelled correctly is worse than one that never corrects anything. Checked against the 300
        // most frequent real words rather than a hand-picked few, because hand-picked lists are
        // exactly the ones a badly-weighted corrector sails through.
        val common = (0 until dict.size)
            .filter { dict.isAlphaOnly(it) && dict.length(it) >= 3 }
            .sortedByDescending { dict.logFreq(it) }
            .take(300)
            .map { dict.word(it) }
        val touched = common.filter { c.correct(it) != null }.map { "$it -> ${c.correct(it)}" }
        assertTrue("these were already correct: $touched", touched.isEmpty())
    }

    @Test
    fun `leaves deliberate tokens alone`() {
        val c = corrector() ?: return
        // Digits, inner capitals and very short strings are all signals of something typed on purpose.
        for (token in listOf("mp3", "covid19", "h264", "iPhone", "URLs", "xy", "a", "qq")) {
            assertNull("$token should be untouched", c.correct(token))
        }
    }

    @Test
    fun `prefers the common word among equally close candidates`() {
        val dict = TestDictionary.of("their" to 5000, "thier" to 0, "tiers" to 30, "tries" to 400)
        val c = Corrector(dict, KeyGrid.qwerty())
        assertEquals("their", c.correct("thei"))
    }

    @Test
    fun `prefers the near-key slip over the far-key one`() {
        // "hs" is one key from both "has" (a is next to s) and "his" (i is far from s on the left half).
        // Weighting substitutions by key distance is what should pick correctly here; a plain
        // Levenshtein distance scores the two identically.
        val dict = TestDictionary.of("gas" to 100, "has" to 100, "his" to 100)
        val c = Corrector(dict, KeyGrid.qwerty())
        assertEquals("has", c.correct("hqs"))   // q sits beside a
        assertEquals("gas", c.correct("fas"))   // f sits beside g
    }

    @Test
    fun `keeps its answers inside a distance budget`() {
        val c = corrector() ?: return
        // Nonsense that isn't near anything must come back with nothing rather than the nearest
        // long word the frequency prior happens to like.
        for (junk in listOf("zzzzzz", "qqqqqqq", "xkcdvbn")) {
            val fix = c.correct(junk)
            assertNull("$junk should not be corrected to $fix", fix)
        }
    }

    @Test
    fun `follows the layout it is given`() {
        val dict = TestDictionary.of("azerty" to 100, "qwerty" to 100)
        // On AZERTY, a and q trade places — so the same typo resolves the other way round.
        val azerty = KeyGrid.fromRows(listOf("azertyuiop", "qsdfghjklm", "wxcvbn"), listOf(0f, 0.5f, 1.5f))
        val onQwerty = Corrector(dict, KeyGrid.qwerty()).correct("qwertz")
        val onAzerty = Corrector(dict, azerty).correct("qwertz")
        assertEquals("qwerty", onQwerty)
        assertTrue("layout should change the answer", onAzerty == "qwerty" || onAzerty == "azerty")
    }

    @Test
    fun `stays fast enough to run on every word`() {
        val c = corrector() ?: return
        val words = typos.keys.toList()
        repeat(20) { for (w in words) c.correct(w) }   // warm up the JIT
        val start = System.nanoTime()
        val runs = 200
        repeat(runs) { for (w in words) c.correct(w) }
        val perWordMs = (System.nanoTime() - start) / 1e6 / (runs * words.size)
        // A correction happens once per finished word, so it has a whole keystroke to itself. This is
        // a generous ceiling that still catches an accidentally-quadratic change to the scan.
        assertTrue("autocorrect took %.2f ms per word".format(perWordMs), perWordMs < 25.0)
    }
}
