package app.lightphonekeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import app.lightphonekeyboard.text.KeyGrid
import java.util.Locale

/**
 * The system keyboard. Once enabled + selected as default, it appears in every text field on the
 * phone. Keystrokes from [LightKeyboardView] are applied to the focused field via InputConnection.
 *
 * Two optional layers sit on top, both toggled in [SetupActivity] and both driven by the bundled word
 * list in [TextEngine]:
 *
 *  - **Autocorrect.** When a tapped word is finished (space / punctuation / enter) it is looked up in
 *    the dictionary, and if it isn't a word the nearest plausible one replaces it — see
 *    [app.lightphonekeyboard.text.Corrector] for how "nearest" is judged. Case is preserved, and the
 *    first backspace afterwards reverts the change. This used to ask the phone's own
 *    [SpellCheckerSession] instead, which LightOS does not ship, so it silently corrected nothing;
 *    that path is still here as a fallback for the case where the bundled dictionary won't load.
 *  - **Swipe typing.** A traced path arrives as [onGesture], is decoded to a word, and is committed
 *    with a trailing space. Backspace immediately afterwards cycles through the runner-up readings of
 *    the same trace instead of deleting — which is how the keyboard offers alternatives without a
 *    suggestion bar, keeping the LightOS look untouched.
 */
class LightImeService : InputMethodService(), LightKeyboardView.Listener, SpellCheckerSessionListener {

    private var keyboard: LightKeyboardView? = null

    private val dictation by lazy { VoiceDictation(this) }

    /** The bundled dictionary plus the corrector and swipe decoder built on it. Loads off-thread. */
    private val engine by lazy { TextEngine(this) }

    // Swipe typing: what the last gesture committed, and the runner-up words it could also have been.
    // While these are set, backspace cycles the alternatives rather than deleting (see [cycleGesture]).
    private var gestureAlternates: List<String>? = null
    private var gestureIndex = 0
    private var gestureCommitted: String? = null
    private var gestureCapitalized = false

    private var spell: SpellCheckerSession? = null
    private val corrections = HashMap<String, String?>()   // word -> fix (null = checked, no fix)
    private val pending = HashMap<Int, String>()           // request sequence -> word
    private var seq = 0

    // Revert-on-backspace: after a correction the text before the cursor ends with [undoFrom];
    // the next backspace restores [undoTo] instead of deleting a character.
    private var undoFrom: String? = null
    private var undoTo: String? = null

    // A word terminated before its spell-check came back. If the fix arrives while "word + terminator"
    // is still sitting at the cursor, we apply it retroactively — covers typing faster than the checker.
    private var lateWord: String? = null
    private var lateTerminator: String? = null

    private var micActive = false

    override fun onCreate() {
        super.onCreate()
        engine.prepare()
        initSpell()
        if (Prefs.voiceEnabled(this)) dictation.prepare()   // warm the model if voice is on (and downloaded)
    }

    override fun onCreateInputView(): View {
        val kb = LightKeyboardView(this)
        kb.listener = this
        keyboard = kb
        return kb
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Only re-initialise the keyboard surface for a genuinely new field. restarting == true is the
        // SAME field reconnecting — many apps call restartInput() after each committed character — so
        // resetting here would snap a user who switched to the numbers/symbols layer back to letters
        // mid-typing (the reported "type one number and it jumps back to ABC" bug).
        if (!restarting) {
            // Number / phone / date fields open on the numbers layer; text fields on letters.
            val cls = info?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0
            val numeric = cls == InputType.TYPE_CLASS_NUMBER ||
                cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME
            keyboard?.reset(numeric)
        }
        micActive = false
        dictation.destroy()
        corrections.clear()
        pending.clear()
        clearUndo()
        clearGesture()
        if (spell == null) initSpell()
        updateShift()
    }

    override fun onDestroy() {
        dictation.destroy()
        spell?.close()
        spell = null
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        updateShift() // after each keystroke/cursor move, recompute uppercase-vs-lowercase
    }

    /** Sentence-case: uppercase at a sentence start, lowercase after — from the field's caps mode.
     *  When Auto-Capitalize is off we report no caps, so there's no auto-uppercase but a manual SHIFT
     *  still one-shots (the next selection update drops it back to lowercase). */
    private fun updateShift() {
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: return
        val caps = Prefs.autoCapitalize(this) && ic.getCursorCapsMode(type) != 0
        keyboard?.setShifted(caps)
    }

    override fun textBeforeCursor(n: Int): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(n, 0)

    // ------------------------------------------------------------------ key events

    override fun onText(s: String) {
        val ic = currentInputConnection ?: return
        lateWord = null                    // any new input invalidates a pending late-correction
        lateTerminator = null
        clearGesture()                     // typing anything ends the swipe-alternatives window
        if (s.length == 1 && isWordChar(s[0])) {
            clearUndo()
            ic.commitText(s, 1)
            requestCheck(trailingWord())   // keep the spell checker warm on the growing word
            return
        }
        // Only whitespace / sentence punctuation finish a word for autocorrect. Digits and other
        // symbols just commit, so alphanumeric tokens (ab2, mp3, covid19) are left alone.
        if (!(s.length == 1 && isCorrectTrigger(s[0]))) {
            clearUndo()
            ic.commitText(s, 1)
            return
        }
        // A word terminator: try to fix the word, then commit [s].
        val original = if (autocorrectOn()) trailingWord() else ""
        val fix = fixFor(original)
        if (fix != null && !fix.equals(original, ignoreCase = true)) {
            val cased = applyCase(original, fix)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1)
            ic.commitText(s, 1)
            ic.endBatchEdit()
            undoFrom = cased + s     // arm revert: text now ends with the fix + terminator
            undoTo = original + s
        } else {
            clearUndo()
            ic.commitText(s, 1)
            // Only the asynchronous spell-checker path can produce a late answer. When the bundled
            // dictionary is doing the work, fixFor() has already decided and there is nothing to wait
            // for — arming a late fix here would let the system checker overrule that decision a
            // moment later, rewriting a word the user had already seen settle.
            if (autocorrectOn() && !engine.ready && original.length >= 2 &&
                !corrections.containsKey(original)
            ) {
                lateWord = original
                lateTerminator = s
            }
        }
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (cycleGesture(ic)) return
        val from = undoFrom
        val to = undoTo
        if (from != null && to != null) {
            val before = ic.getTextBeforeCursor(from.length, 0)?.toString()
            clearUndo()
            if (before == from) {   // only revert if the corrected text is still sitting there
                ic.beginBatchEdit()
                ic.deleteSurroundingText(from.length, 0)
                ic.commitText(to, 1)
                ic.endBatchEdit()
                return
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        // Delete a whole grapheme cluster, not one UTF-16 unit — otherwise an emoji (surrogate pair /
        // variation selector) gets half-deleted and the leftover renders as a white box.
        val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBACK, 0)
        ic.deleteSurroundingText(lastGraphemeLength(before), 0)
    }

    override fun onBackspaceWord() {
        val ic = currentInputConnection ?: return
        clearUndo()
        clearGesture()
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        if (before.isEmpty()) { ic.deleteSurroundingText(1, 0); return }
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--    // trailing whitespace
        while (i > 0 && !before[i - 1].isWhitespace()) i--   // then the word
        val count = before.length - i
        ic.deleteSurroundingText(if (count > 0) count else 1, 0)
    }

    /** Length (in chars) of the last grapheme cluster of [before]; 1 if empty/unknown. */
    private fun lastGraphemeLength(before: CharSequence?): Int {
        if (before.isNullOrEmpty()) return 1
        val s = before.toString()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.last()
        val start = it.previous()
        return if (start == java.text.BreakIterator.DONE) s.length else (end - start)
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        clearGesture()
        // Fix the last word before firing the action / newline.
        if (autocorrectOn()) {
            val original = trailingWord()
            val fix = fixFor(original)
            if (fix != null && !fix.equals(original, ignoreCase = true)) {
                val cased = applyCase(original, fix)
                ic.beginBatchEdit()
                ic.deleteSurroundingText(original.length, 0)
                ic.commitText(cased, 1)
                ic.endBatchEdit()
            }
        }
        clearUndo()
        // Honor the field's action (Send/Search/Go); otherwise insert a newline.
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    /** Auto-Period: a quick second space turns the trailing " " into ". " — but only after a letter
     *  or digit, so a double space at line start or after punctuation just stays two spaces. The IME
     *  owns the text, so the rewrite happens here; the view only detects the double tap. */
    override fun onDoubleSpace() {
        val ic = currentInputConnection ?: return
        clearUndo()
        clearGesture()
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)   // drop the lone trailing space…
            ic.commitText(". ", 1)           // …and replace it with period + space
            ic.endBatchEdit()
        } else {
            ic.commitText(" ", 1)            // not eligible — behave like a normal space
        }
    }

    override fun onDismiss() {
        requestHideSelf(0) // swipe-down closes the keyboard, the proper Android way
    }

    // Never take over the whole screen with the big white "extract" editor (it appears in landscape by
    // default). Our keyboard is built for the compact LightOS layout, so keep it docked at the bottom.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onMic() {
        if (!Prefs.voiceEnabled(this)) return   // mic key is hidden when voice is off, but guard anyway
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // An IME can't pop the permission dialog itself; the shim activity does it.
            startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val kb = keyboard ?: return
        micActive = true
        kb.startListeningUi()
        startDictationWhenReady(kb, attempts = 0)
    }

    /** Wait (briefly) for the model to finish unpacking on first use, then start listening. */
    private fun startDictationWhenReady(kb: LightKeyboardView, attempts: Int) {
        if (!micActive) return
        if (dictation.ready) {
            dictation.listen(
                onPartial = { kb.setListeningStatus(it) },
                // Each finished segment commits to the field; dictation keeps going across pauses.
                onSegment = { text ->
                    clearUndo()
                    clearGesture()
                    currentInputConnection?.commitText(spacedDictation(text), 1)
                },
                onError = { msg ->
                    micActive = false
                    kb.setListeningStatus(msg)
                    kb.postDelayed({ kb.stopListeningUi() }, 1200)
                },
            )
            return
        }
        dictation.prepare()
        if (attempts > 40) {   // ~12s; first-run model unpack should be done well before this
            micActive = false
            kb.setListeningStatus("Voice unavailable")
            kb.postDelayed({ kb.stopListeningUi() }, 1200)
            return
        }
        kb.setListeningStatus("Preparing voice…")
        kb.postDelayed({ startDictationWhenReady(kb, attempts + 1) }, 300)
    }

    /** Tap on the listening surface = "I'm done": flush the trailing words, then close it. */
    override fun onMicCancel() {
        if (!micActive) return
        micActive = false
        dictation.stop()
        keyboard?.stopListeningUi()
    }

    /** Insert a leading space if the cursor isn't already at a boundary, so dictated text doesn't fuse. */
    private fun spacedDictation(text: String): String {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        return if (before.isNotEmpty() && !before.last().isWhitespace()) " $text" else text
    }

    // Tell any of our own overlays (e.g. light-assistant's edge seam) to get out of the way while the
    // keyboard is on screen, so they don't sit over the top-left keys.
    override fun onWindowShown() { super.onWindowShown(); broadcastImeVisible(true) }
    override fun onWindowHidden() { super.onWindowHidden(); broadcastImeVisible(false) }
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (micActive) { micActive = false; dictation.destroy(); keyboard?.stopListeningUi() }
        broadcastImeVisible(false)
    }

    private fun broadcastImeVisible(visible: Boolean) {
        runCatching { sendBroadcast(Intent(ACTION_IME_VISIBILITY).putExtra(EXTRA_VISIBLE, visible)) }
    }

    // ------------------------------------------------------------------ swipe typing

    override fun onKeyGrid(grid: KeyGrid) {
        // The view has laid out; tell the corrector and the decoder where the letters actually are.
        engine.setKeyGrid(grid)
    }

    /**
     * A finished trace. Decode it, commit the best reading plus a trailing space, and remember the
     * runner-ups so backspace can cycle them.
     *
     * The trailing space is what makes swiping faster than tapping — otherwise every word still needs
     * a deliberate space afterwards. [needsLeadingSpace] handles the other side, so swiping two words
     * in a row doesn't fuse them, and swiping mid-sentence doesn't double a space already there.
     *
     * If nothing decodes, nothing is committed. The letter the traced key typed on touch-down has
     * already been retracted by the view, so a rejected gesture leaves the field exactly as it was —
     * the right outcome for a stray drag.
     */
    override fun onGesture(xs: FloatArray, ys: FloatArray, count: Int) {
        val ic = currentInputConnection ?: return
        if (!Prefs.swipeTyping(this)) return
        val decoder = engine.decoder ?: return          // dictionary still loading, or unavailable
        val words = decoder.decode(xs, ys, count, GESTURE_ALTERNATES)
        if (words.isEmpty()) return
        clearUndo()
        gestureCapitalized = keyboard?.isShifted == true
        val text = gestureText(words[0])
        ic.commitText(text, 1)
        gestureAlternates = words
        gestureIndex = 0
        gestureCommitted = text
    }

    /**
     * Backspace straight after a swipe: replace the committed word with the trace's next-best reading
     * instead of deleting a character. This is how alternatives are offered without a suggestion bar.
     *
     * Bails out — letting backspace delete normally — once the readings are exhausted, or if the text
     * at the cursor is no longer what we committed (the user moved the caret, or another app rewrote
     * the field), so we never overwrite something we didn't put there.
     */
    private fun cycleGesture(ic: InputConnection): Boolean {
        val alts = gestureAlternates ?: return false
        val committed = gestureCommitted ?: return false
        if (gestureIndex + 1 >= alts.size) { clearGesture(); return false }
        if (ic.getTextBeforeCursor(committed.length, 0)?.toString() != committed) {
            clearGesture()
            return false
        }
        gestureIndex++
        val next = gestureText(alts[gestureIndex])
        ic.beginBatchEdit()
        ic.deleteSurroundingText(committed.length, 0)
        ic.commitText(next, 1)
        ic.endBatchEdit()
        gestureCommitted = next
        return true
    }

    /** A decoded word dressed for insertion: leading space if needed, the user's case, trailing space. */
    private fun gestureText(word: String): String {
        val cased = if (gestureCapitalized) word.replaceFirstChar { it.uppercaseChar() } else word
        return if (needsLeadingSpace()) " $cased " else "$cased "
    }

    /** True when the character before the cursor is neither absent nor whitespace. */
    private fun needsLeadingSpace(): Boolean {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        return before.isNotEmpty() && !before.last().isWhitespace()
    }

    private fun clearGesture() {
        gestureAlternates = null
        gestureCommitted = null
        gestureIndex = 0
    }

    // ------------------------------------------------------------------ spell checking

    private fun initSpell() {
        val tsm = getSystemService(TextServicesManager::class.java) ?: return
        // referToSpellCheckerLanguageSettings = false: use the locale directly so we don't depend on
        // the global spell-check toggle being explicitly enabled.
        spell = tsm.newSpellCheckerSession(null, Locale.getDefault(), this, false)
    }

    private fun autocorrectOn(): Boolean = Prefs.autocorrect(this)

    /**
     * The replacement for a just-finished word, or null to leave it alone.
     *
     * The bundled dictionary answers if it has loaded — synchronously, which is why this can run at the
     * moment the word ends rather than needing a result warmed up in advance. Otherwise it falls back
     * to whatever the phone's spell checker had to say, which is the pre-existing path and on LightOS
     * is normally nothing at all.
     */
    private fun fixFor(word: String): String? {
        if (word.length < 2) return null
        engine.corrector?.let { return it.correct(word) }
        return corrections[word]
    }

    /** Ask the device spell checker about [word] (once); the answer lands in [corrections]. */
    private fun requestCheck(word: String) {
        if (!autocorrectOn()) return
        if (engine.ready) return   // the bundled dictionary answers synchronously; no need to warm this
        val s = spell ?: return
        if (word.length < 2 || word.length > 32) return
        if (corrections.containsKey(word)) return
        if (word.any { it.isDigit() } || word.drop(1).any { it.isUpperCase() }) return // acronyms/odd
        val id = seq++
        pending[id] = word
        @Suppress("DEPRECATION")
        s.getSuggestions(arrayOf(TextInfo(word, 0, id)), 3, true)
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        results ?: return
        for (si in results) {
            val word = pending.remove(si.sequence) ?: continue
            val fix = pickFix(si)
            corrections[word] = fix
            if (word == lateWord && fix != null && !fix.equals(word, ignoreCase = true)) {
                applyLateFix(word, fix)
            }
        }
    }

    /** A spell-check result came back after the word was already terminated. If "word + terminator" is
     *  still sitting right before the cursor (the user hasn't typed on), swap in the fix now. */
    private fun applyLateFix(original: String, fix: String) {
        val ic = currentInputConnection ?: return
        val term = lateTerminator ?: return
        lateWord = null
        lateTerminator = null
        val tail = original + term
        if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) return
        val cased = applyCase(original, fix)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(tail.length, 0)
        ic.commitText(cased + term, 1)
        ic.endBatchEdit()
        undoFrom = cased + term
        undoTo = original + term
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        // Unused — we drive everything through the per-word getSuggestions path above.
    }

    /** The top suggestion whenever the checker flags the word as a typo (i.e. what gets the red
     *  underline) and offers one. We don't require the stricter "recommended" flag — if a word is
     *  flagged wrong we fix it, and an over-eager fix is a single backspace to undo. Real, in-dictionary
     *  words are always left alone. */
    private fun pickFix(si: SuggestionsInfo): String? {
        val attr = si.suggestionsAttributes
        if (attr and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0) return null
        val typo = attr and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
        if (!typo || si.suggestionsCount <= 0) return null
        return si.getSuggestionAt(0)
    }

    // ------------------------------------------------------------------ helpers

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\''

    /** Characters that "finish" a word and may trigger autocorrect — whitespace + sentence punctuation. */
    private fun isCorrectTrigger(c: Char): Boolean = c.isWhitespace() || c in ".,!?;:)"

    /** The run of word characters immediately before the cursor. */
    private fun trailingWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0) ?: return ""
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.substring(i).toString()
    }

    /** Match the suggestion's case to what the user typed (ALL CAPS / Capitalized / lower). */
    private fun applyCase(original: String, fix: String): String = when {
        original.length > 1 && original.all { it.isUpperCase() } -> fix.uppercase()
        original.firstOrNull()?.isUpperCase() == true -> fix.replaceFirstChar { it.uppercaseChar() }
        else -> fix
    }

    private fun clearUndo() {
        undoFrom = null
        undoTo = null
    }

    companion object {
        /** Broadcast so our overlays can dodge the keyboard. Implicit; caught by a runtime receiver. */
        const val ACTION_IME_VISIBILITY = "app.lightphonekeyboard.IME_VISIBILITY"
        const val EXTRA_VISIBLE = "visible"
        /** Window of text to inspect when deleting the last grapheme cluster (covers long emoji). */
        private const val GRAPHEME_LOOKBACK = 16
        /** How many readings of one trace to keep, i.e. how many times backspace can cycle. */
        private const val GESTURE_ALTERNATES = 4
    }
}
