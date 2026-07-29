package app.lightphonekeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The personal word list, and the three separate things adding a word has to fix. A name that is only
 * half-added — say, no longer corrected away but still not suggestable — is the kind of gap that reads
 * as the feature not working.
 */
class UserWordsTest {

    private fun corrector(user: UserWords): Corrector? =
        TestDictionary.bundled()?.let { Corrector(it, KeyGrid.qwerty()).apply { userWords = user } }

    @Test
    fun `stores what was typed and matches case-insensitively`() {
        val u = UserWords.of(listOf("Basil", "Alex", "Drexel"))
        assertEquals(listOf("Basil", "Alex", "Drexel"), u.entries)
        assertTrue(u.contains("basil"))
        assertTrue(u.contains("BASIL"))
        assertEquals("Basil", u.displayOf("basil"))
        assertFalse(u.contains("basi"))
    }

    @Test
    fun `rejects what the keyboard could never type`() {
        val u = UserWords.of(listOf("Basil", "a", "", "  ", "hi there", "x9", "Zoë", "'"))
        // Single letters and blanks are noise; a space is two words; a digit can't be part of a word.
        // An accented letter is allowed in — it is a letter — but is dropped from the searchable
        // dictionary, which only holds a-z.
        assertEquals(listOf("Basil", "Zoë"), u.entries)
        assertEquals(1, u.dictionary?.size)
    }

    @Test
    fun `deduplicates regardless of case and survives a round trip`() {
        val u = UserWords.of(listOf("Basil", "basil", "BASIL", "Alex"))
        assertEquals(listOf("Basil", "Alex"), u.entries)
        assertEquals(u.entries, UserWords.deserialize(u.serialize()).entries)
        assertEquals(emptyList<String>(), UserWords.deserialize(null).entries)
        assertEquals(emptyList<String>(), UserWords.deserialize("").entries)
    }

    @Test
    fun `adding and removing`() {
        var u = UserWords.EMPTY.withWord("Basil").withWord("Alex")
        assertEquals(listOf("Basil", "Alex"), u.entries)
        u = u.withWord("basil")                       // already there, in another case
        assertEquals(2, u.size)
        u = u.withWord("q")                           // too short to be useful
        assertEquals(2, u.size)
        u = u.without("BASIL")                        // removal is case-insensitive too
        assertEquals(listOf("Alex"), u.entries)
    }

    @Test
    fun `an added name is no longer corrected away`() {
        // The reason the list exists. The premise is asserted first, so this test fails loudly if these
        // names ever stop being mangled rather than quietly passing for the wrong reason.
        //
        // Note which names these are, because picking them carelessly makes the test meaningless in two
        // different ways. "Basil" and "Alex" are already in SCOWL (a herb, and a word) so they were
        // never broken. "Siobhan" and "Ngozi" are absent but sit near nothing, so they are left alone
        // anyway. These five are absent AND close to a common word, which is the case that actually
        // hurts: without the list they become lupe, go, life, born, ravine.
        val names = listOf("Lupo", "Gio", "Aoife", "Bjorn", "Ravindra")
        val before = corrector(UserWords.EMPTY) ?: return
        val untouched = names.filter { before.correct(it.lowercase()) == null }
        assertTrue("these are already dictionary words, so they test nothing: $untouched", untouched.isEmpty())

        val after = corrector(UserWords.of(names)) ?: return
        val mangled = names.filter { after.correct(it.lowercase()) != null || after.correct(it) != null }
        assertTrue("still corrected after being added: $mangled", mangled.isEmpty())
    }

    @Test
    fun `a typo can be corrected to an added name`() {
        val c = corrector(UserWords.of(listOf("Ravindra", "Nakamura"))) ?: return
        // The capitalisation the user entered comes back, which is the point for a name.
        assertEquals("Ravindra", c.correct("ravindar"))   // a transposition
        assertEquals("Nakamura", c.correct("nakamura".dropLast(1) + "ar"))
    }

    @Test
    fun `an added name can be swiped`() {
        val dict = TestDictionary.bundled() ?: return
        val grid = KeyGrid.qwerty()
        val d = GestureDecoder(dict, grid)
        d.userWords = UserWords.of(listOf("Siobhan"))
        // Trace the name straight through its key centres.
        val letters = "siobhan"
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 1 until letters.length) {
            val a = letters[i - 1]
            val b = letters[i]
            val steps = 8
            for (k in 0 until steps) {
                val t = k.toFloat() / steps
                xs.add(grid.x(a) + (grid.x(b) - grid.x(a)) * t)
                ys.add(grid.y(a) + (grid.y(b) - grid.y(a)) * t)
            }
        }
        xs.add(grid.x(letters.last()))
        ys.add(grid.y(letters.last()))
        val got = d.decode(xs.toFloatArray(), ys.toFloatArray(), xs.size, 4)
        assertTrue("expected Siobhan somewhere in $got", "Siobhan" in got)
    }

    @Test
    fun `an added name is offered as a completion`() {
        val dict = TestDictionary.bundled() ?: return
        val c = Corrector(dict, KeyGrid.qwerty())
        val s = Suggester(dict, c)
        val user = UserWords.of(listOf("Siobhan"))
        c.userWords = user
        s.userWords = user
        assertTrue("expected Siobhan for 'sio', got ${s.forPrefix("sio")}", "Siobhan" in s.forPrefix("sio"))
    }
}
