package app.lightphonekeyboard

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.lightphonekeyboard.text.ForgottenWords
import app.lightphonekeyboard.text.UserWords

/**
 * The personal word list — names, places, anything the bundled dictionary has never heard of. Opened
 * from [SetupActivity]. Pure B/W and text-first, matching the rest of setup.
 *
 * Adding a word does three things at once, which is why it is worth a screen of its own: the word stops
 * being autocorrected into something else, becomes a word autocorrect can arrive *at*, and becomes
 * traceable by swipe. Without it a name like "Bjorn" is rewritten to "born" every time it is typed.
 *
 * Below it, the words held down in the suggestion strip to be forgotten. They live on this screen rather
 * than a new one because a destructive gesture needs somewhere to be undone — a long-press that silently
 * deletes with no way back is a trap — and because the two lists are the same idea pointing in opposite
 * directions. The section is hidden entirely while nothing has been forgotten, so the screen looks no
 * different for anyone who never uses the gesture.
 *
 * Writes straight to [Prefs] on each change; the running keyboard re-reads the list the next time it
 * opens (see TextEngine.reloadUserWords), since an Activity and the IME share only preferences.
 */
class UserWordsActivity : AppCompatActivity() {

    private var words = UserWords.EMPTY
    private var forgotten = ForgottenWords.EMPTY
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var empty: TextView
    private lateinit var forgottenSection: LinearLayout
    private lateinit var forgottenList: LinearLayout
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        words = UserWords.deserialize(Prefs.userWords(this))
        forgotten = ForgottenWords.deserialize(Prefs.forgottenWords(this))

        pad = (24 * resources.displayMetrics.density).toInt()
        val side = (34 * resources.displayMetrics.density).toInt()   // LightOS horizontal content inset
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, pad, side, pad)
        }

        // The Light Phone has no reliable system back, so give an explicit way out.
        root.addView(
            label(getString(R.string.words_back), 18f, R.color.white).apply {
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setOnClickListener { finish() }
            },
        )
        root.addView(label(getString(R.string.words_title), 28f, R.color.white))
        root.addView(label(getString(R.string.words_blurb), 14f, R.color.gray))

        input = EditText(this).apply {
            hint = getString(R.string.words_hint)
            setHintTextColor(getColor(R.color.gray))
            setTextColor(getColor(R.color.white))
            textSize = 22f
            setBackgroundColor(getColor(R.color.black))
            // No autocorrect or auto-capitalisation on this field of all fields: the whole point is to
            // enter a word the dictionary doesn't know, and correcting it here would defeat that.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> addTyped(); true }
        }
        val addButton = label(getString(R.string.words_add), 20f, R.color.white).apply {
            isClickable = true
            setOnClickListener { addTyped() }
        }
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
                addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(addButton)
            },
        )

        empty = label(getString(R.string.words_empty), 16f, R.color.gray)
        root.addView(empty)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        forgottenList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        forgottenSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
            addView(label(getString(R.string.forgotten_title), 22f, R.color.white))
            addView(label(getString(R.string.forgotten_blurb), 14f, R.color.gray))
            addView(forgottenList)
        }
        root.addView(forgottenSection)

        setContentView(
            LightScrollView(this).apply {
                setBackgroundColor(getColor(R.color.black))
                addView(root)
            },
        )
        refresh()
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(color))
        textSize = size
        setPadding(0, pad / 3, 0, pad / 3)
    }

    private fun addTyped() {
        val typed = input.text.toString().trim()
        if (typed.isEmpty()) return
        if (!UserWords.isAcceptable(typed)) {
            // Say why rather than silently dropping it — a rejected word with no explanation reads as
            // the screen being broken.
            input.error = getString(R.string.words_rejected)
            return
        }
        words = words.withWord(typed)
        Prefs.setUserWords(this, words.serialize())
        input.setText("")
        refresh()
    }

    private fun remove(word: String) {
        words = words.without(word)
        Prefs.setUserWords(this, words.serialize())
        refresh()
    }

    /** Put a forgotten word back into circulation. */
    private fun restore(word: String) {
        forgotten = forgotten.without(word)
        Prefs.setForgottenWords(this, forgotten.serialize())
        refresh()
    }

    /** Rebuild both lists. They are a handful of rows, so replacing them all is simpler than diffing. */
    private fun refresh() {
        list.removeAllViews()
        empty.visibility = if (words.size == 0) TextView.VISIBLE else TextView.GONE
        for (word in words.entries) {
            list.addView(row(word, getString(R.string.words_remove, word)) { remove(word) })
        }
        forgottenList.removeAllViews()
        // No empty state for this one: nothing has been forgotten is the normal condition, and a section
        // explaining a gesture the user may never have used is clutter on a 3.9" screen.
        forgottenSection.visibility = if (forgotten.isEmpty) LinearLayout.GONE else LinearLayout.VISIBLE
        for (word in forgotten.entries) {
            forgottenList.addView(row(word, getString(R.string.forgotten_restore, word)) { restore(word) })
        }
    }

    /** One list row: the word, and a "✕" that takes it out of whichever list it is in. */
    private fun row(word: String, describe: String, onRemove: () -> Unit): LinearLayout {
        val nameView = TextView(this).apply {
            text = word
            setTextColor(getColor(R.color.white))
            textSize = 22f
        }
        // A "✕" rather than a swipe or a long-press: both are invisible affordances, and this screen
        // has no room to teach one.
        val removeView = TextView(this).apply {
            text = "✕"
            setTextColor(getColor(R.color.gray))
            textSize = 22f
            setPadding(pad / 2, 0, 0, 0)
            isClickable = true
            contentDescription = describe
            setOnClickListener { onRemove() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad / 2, 0, pad / 2)
            addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(removeView)
        }
    }
}
