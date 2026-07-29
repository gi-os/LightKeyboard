package app.lightphonekeyboard

import android.content.Context
import android.util.Log
import app.lightphonekeyboard.text.Corrector
import app.lightphonekeyboard.text.Dictionary
import app.lightphonekeyboard.text.GestureDecoder
import app.lightphonekeyboard.text.KeyGrid
import app.lightphonekeyboard.text.Suggester
import app.lightphonekeyboard.text.UserWords

/**
 * Owns the bundled dictionary and the two things built on it: [Corrector] (autocorrect for tapped
 * words) and [GestureDecoder] (swipe typing).
 *
 * The dictionary is ~800 KB of text and takes a beat to parse, so it loads on a background thread the
 * first time the keyboard is created. Until it lands, every accessor here returns null and the
 * keyboard behaves exactly as it did before — taps type letters, swipes do nothing. That way a slow
 * first load can never stall the first keypress, and a corrupt asset degrades to a plain keyboard
 * instead of crashing the IME (which on a phone means no keyboard at all, anywhere).
 *
 * One instance per IME process; [corrector] and [decoder] are only touched from the IME thread.
 */
class TextEngine(private val context: Context) {

    @Volatile
    private var dictionary: Dictionary? = null

    @Volatile
    var corrector: Corrector? = null
        private set

    @Volatile
    var decoder: GestureDecoder? = null
        private set

    @Volatile
    var suggester: Suggester? = null
        private set

    /** The user's own words, loaded from prefs and pushed into everything that searches. */
    @Volatile
    var userWords: UserWords = UserWords.EMPTY
        private set

    private var loading = false

    val ready: Boolean get() = dictionary != null

    /** Kick off the one-time load. Safe to call repeatedly; only the first call does work. */
    @Synchronized
    fun prepare() {
        if (dictionary != null || loading) return
        loading = true
        Thread({
            val loaded = try {
                context.resources.openRawResource(R.raw.words).use { Dictionary.load(it) }
            } catch (e: Exception) {
                // Missing or corrupt asset. Autocorrect falls back to the system spell checker and
                // swipe typing stays off; the keyboard itself is unaffected.
                Log.w(TAG, "dictionary unavailable, falling back to the system spell checker", e)
                null
            }
            if (loaded != null) {
                val c = Corrector(loaded)
                val d = GestureDecoder(loaded)
                val s = Suggester(loaded, c)
                pendingGrid?.let { c.grid = it; d.grid = it }
                val words = UserWords.deserialize(Prefs.userWords(context))
                c.userWords = words
                d.userWords = words
                s.userWords = words
                userWords = words
                corrector = c
                decoder = d
                suggester = s
                dictionary = loaded
            }
            loading = false
        }, "light-kb-dict").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Held so a layout that happened before the load finished still reaches the two consumers. */
    @Volatile
    private var pendingGrid: KeyGrid? = null

    /** Called by the keyboard view after every relayout, so both models use the real key geometry. */
    fun setKeyGrid(grid: KeyGrid) {
        pendingGrid = grid
        corrector?.grid = grid
        decoder?.grid = grid
    }

    /** True if [word] is a real word — used to skip correcting something the user spelled right. */
    fun isWord(word: String): Boolean =
        dictionary?.contains(word.lowercase()) == true || userWords.contains(word)

    /**
     * Re-read the personal word list. Called when the keyboard opens, because the settings screen may
     * have changed it in the meantime — it is a separate Activity in the same process, so there is no
     * live connection between the two beyond the shared preferences.
     */
    fun reloadUserWords() {
        val words = UserWords.deserialize(Prefs.userWords(context))
        if (words.entries == userWords.entries) return
        userWords = words
        corrector?.userWords = words
        decoder?.userWords = words
        suggester?.userWords = words
    }

    private companion object {
        const val TAG = "LightKeyboard"
    }
}
