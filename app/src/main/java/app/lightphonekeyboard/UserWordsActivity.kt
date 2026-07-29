package app.lightphonekeyboard

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.lightphonekeyboard.text.UserWords

/**
 * The personal word list — names, places, anything the bundled dictionary has never heard of. Opened
 * from [SetupActivity]. Pure B/W and text-first, matching the rest of setup.
 *
 * Adding a word does three things at once, which is why it is worth a screen of its own: the word stops
 * being autocorrected into something else, becomes a word autocorrect can arrive *at*, and becomes
 * traceable by swipe. Without it a name like "Bjorn" is rewritten to "born" every time it is typed.
 *
 * Writes straight to [Prefs] on each change; the running keyboard re-reads the list the next time it
 * opens (see TextEngine.reloadUserWords), since an Activity and the IME share only preferences.
 */
class UserWordsActivity : AppCompatActivity() {

    private var words = UserWords.EMPTY
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var empty: TextView
    private var pad = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        words = UserWords.deserialize(Prefs.userWords(this))

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

    /** Rebuild the list. It is a handful of rows, so replacing them all is simpler than diffing. */
    private fun refresh() {
        list.removeAllViews()
        empty.visibility = if (words.size == 0) TextView.VISIBLE else TextView.GONE
        for (word in words.entries) {
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
                contentDescription = getString(R.string.words_remove, word)
                setOnClickListener { remove(word) }
            }
            list.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, pad / 2, 0, pad / 2)
                    addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(removeView)
                },
            )
        }
    }
}
