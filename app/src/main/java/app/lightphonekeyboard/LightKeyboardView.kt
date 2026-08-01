package app.lightphonekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import app.lightphonekeyboard.text.GestureDecoder
import app.lightphonekeyboard.text.KeyGrid
import app.lightphonekeyboard.text.StripItem
import app.lightphonekeyboard.text.Suggester
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Black-and-white keyboard view, matched to the LightOS keyboard. UI-only: it reports key events
 * through [Listener]; the host [LightImeService] applies them to the focused field via InputConnection.
 *
 * This is a single self-drawing surface rather than one Android view per key. That matters for
 * typing accuracy:
 *   - Key hit rects *tile the whole surface with no gaps* (the visible gutters are drawn inside each
 *     cell), so there are no dead zones — every pixel, including the very corners, maps to a key.
 *     A point that somehow lands outside all rects snaps to the nearest key center.
 *   - Keys commit on touch-DOWN, and the key is locked in at down. The old per-view onClick fired on
 *     UP and cancelled if the finger drifted off the key — which is exactly what happens when you
 *     "roll" between keys typing fast, so letters were being dropped. Committing on down removes both
 *     the latency and the drift-cancellation.
 *   - Touches are tracked per pointer, so overlapping/rolling presses each register.
 *
 * Holding still and then swiping DOWN closes the keyboard ([Listener.onDismiss]).
 *
 * Dragging ACROSS the letters is swipe typing: the path is collected in key units and handed to the
 * host as [Listener.onGesture], which decodes it to a word. The two gestures don't collide because
 * dismiss is only even considered once the finger has sat still for [DISMISS_HOLD_MS] — a real word
 * trace is already past its 22dp trace-start distance well before that clock runs out, so it never
 * reaches the dismiss check at all; see [onTouchEvent]. Requiring the hold is what separates them:
 * without it, a swipe that happens to start moving mostly downward (tracing "no" or "on", say) races
 * the same two thresholds a deliberate dismiss does, and which one wins depends on touch-sample timing
 * rather than what the user meant. The character the first key committed on touch-down is retracted
 * the moment either gesture is recognised, so neither leaves a stray letter behind.
 *
 * Future: Apple-style dynamic target resizing (silently growing the hit rects of likely next letters
 * from a language model while the visible keys stay put) would build on this tiled-rect foundation.
 */
class LightKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onText(s: String)
        fun onBackspace()
        /** Delete the previous whole word (key-repeat escalates to this after a long hold). */
        fun onBackspaceWord()
        fun onEnter()
        /** Space tapped twice quickly (Auto-Period): turn the trailing space into ". ". */
        fun onDoubleSpace()
        fun onDismiss()
        /** Up to [n] characters immediately before the cursor, for the typing-accuracy context model. */
        fun textBeforeCursor(n: Int): CharSequence?
        /** Mic key tapped — start voice dictation. */
        fun onMic()
        /** Listening surface tapped — cancel dictation. */
        fun onMicCancel()

        /**
         * A finished swipe-typing trace. [xs]/[ys] hold the first [count] points in *key units*
         * (x / key width, y / row pitch), matching the geometry reported by [onKeyGrid], so the host
         * can decode without knowing anything about pixels.
         */
        fun onGesture(xs: FloatArray, ys: FloatArray, count: Int)

        /** The laid-out a-z key positions, in key units. Re-sent on every relayout. */
        fun onKeyGrid(grid: KeyGrid)

        /** A slot tapped in the suggestion strip. [StripItem.literal] marks the keep-as-typed slot. */
        fun onSuggestion(item: StripItem)

        /**
         * A slot held down in the suggestion strip: forget this word, don't insert it. Never fired for
         * the keep-as-typed slot, which is the user's own spelling and has nothing to forget.
         */
        fun onSuggestionForget(item: StripItem)
    }

    var listener: Listener? = null

    private object Key {
        const val SHIFT = "__SHIFT__"
        const val BACKSPACE = "__BKSP__"
        const val SPACE = "__SPACE__"
        const val ENTER = "__ENTER__"
        const val EMOJI = "__EMOJI__"
        const val EMOJI_BACK = "__EMOJI_BACK__"
        const val MIC = "__MIC__"
        const val SYMBOLS = "123"
        const val LETTERS = "ABC"
        const val MORE = "#+="
    }

    private object Layout {
        val letters = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf(Key.SHIFT, "z", "x", "c", "v", "b", "n", "m", Key.BACKSPACE),
            listOf(Key.SYMBOLS, Key.EMOJI, Key.SPACE, Key.MIC, Key.ENTER),
        )
        // French AZERTY and German QWERTZ — same control keys, only the three letter rows differ.
        val azerty = listOf(
            listOf("a", "z", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("q", "s", "d", "f", "g", "h", "j", "k", "l", "m"),
            listOf(Key.SHIFT, "w", "x", "c", "v", "b", "n", Key.BACKSPACE),
            listOf(Key.SYMBOLS, Key.EMOJI, Key.SPACE, Key.MIC, Key.ENTER),
        )
        val qwertz = listOf(
            listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf(Key.SHIFT, "y", "x", "c", "v", "b", "n", "m", Key.BACKSPACE),
            listOf(Key.SYMBOLS, Key.EMOJI, Key.SPACE, Key.MIC, Key.ENTER),
        )
        val symbols = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
            listOf(Key.MORE, ".", ",", "?", "!", "'", Key.BACKSPACE),
            listOf(Key.LETTERS, Key.EMOJI, Key.SPACE, Key.MIC, Key.ENTER),
        )
        val more = listOf(
            listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
            listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥"),
            listOf(Key.SYMBOLS, ".", ",", "?", "!", "'", Key.BACKSPACE),
            listOf(Key.LETTERS, Key.EMOJI, Key.SPACE, Key.MIC, Key.ENTER),
        )
        val emoji = listOf(
            "😅", "😊", "🙃", "😍", "😜", "😂", "😭", "😎",
            "🙌", "👍", "👎", "🤞", "✌️", "👌", "👋", "🙏",
            "✨", "🔥", "❤️", "💔", "🏆", "🎯", "👑", "👀",
        )
    }

    private enum class Layer { LETTERS, SYMBOLS, MORE, EMOJI }

    private var layer = Layer.LETTERS
    private var shifted = true
    private var capsLock = false           // double-tap shift → stays uppercase until tapped off
    private var lastShiftTapMs = 0L
    private var lastSpaceTapMs = 0L        // for Auto-Period (double-tap space → ". ")

    // Prefs cached on reset()/init so the layout pass doesn't re-read SharedPreferences per row.
    private var keyLayout = Prefs.LAYOUT_QWERTY
    private var autoPeriod = true
    private var swipeTyping = true
    private var suggestionsOn = false
    private val hiddenKeys = HashSet<String>()   // control keys removed by their settings toggles

    // Voice-dictation listening overlay (drawn instead of keys while the recognizer is active).
    private var listening = false
    private var listeningStatus = ""

    // Backspace held → repeat deleting chars, then escalate to whole words after a long hold.
    private var backspacePointerId = -1
    private var backspaceDownMs = 0L
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - backspaceDownMs >= BACKSPACE_WORD_AFTER_MS) {
                listener?.onBackspaceWord()
                postDelayed(this, BACKSPACE_WORD_INTERVAL_MS)
            } else {
                listener?.onBackspace()
                postDelayed(this, BACKSPACE_CHAR_INTERVAL_MS)
            }
        }
    }

    // Suggestion held down → forget that word. Fires from the same posted-Runnable pattern as the
    // backspace repeat above; there is no GestureDetector anywhere in this view and adding one only for
    // the strip would put a second, differently-tuned idea of "long press" beside the existing one.
    private val suggestionHold = Runnable {
        val slot = pressedSuggestion
        val item = if (slot >= 0) suggestionAt(slot) else null
        // The literal is the word the user just typed. There is nothing learned to remove, and eating
        // the tap on it would break the one slot whose whole job is to be tapped.
        if (item != null && !item.literal) {
            suggestionForgotten = true
            pressedSuggestion = -1
            invalidate()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onSuggestionForget(item)
        }
    }
    /** Set once a hold has fired, so the finger lifting afterwards doesn't also insert the word. */
    private var suggestionForgotten = false

    /** One key with its (gapless) hit rect and its inset, drawn-to rect. */
    private class PlacedKey(val id: String, val hit: RectF, val vis: RectF) {
        val cx get() = vis.centerX()
        val cy get() = vis.centerY()
    }

    private val placed = ArrayList<PlacedKey>()
    private val letterKeys = ArrayList<PlacedKey>()   // a-z keys only, for the accuracy model

    // --- metrics (px), set by applyPrefs() for the active height preset ---
    // Medium matches the LightOS keyboard; Short tightens the gutters and shortens the keys, Tall does
    // the opposite. The accuracy model is shared across all three: its spatial term is normalised by the
    // live key width / rowPitch (so it rescales itself), and its language term (charmodel.bin) is
    // geometry-independent — see the "typing accuracy" section below. Only the px-stored learned vertical
    // offsets are height-specific, so applyPrefs() resets them when the preset changes.
    private var compact = false                 // derived: true on Short (tighter control-key icon insets)
    private var appliedHeight: String? = null   // last preset applyPrefs() ran for, to detect a change
    private var padTop = 0f
    private var padBottom = 0f
    private var padSide = 0f
    private var keyGap = 0f             // half the visible gutter; applied as an inset on each side
    private var rowKeyH = 0f
    private var rowPitch = 0f           // rowKeyH + keyGap*2, one row band
    private var keyTextSize = 0f        // single-character key label
    private var labelTextSize = 0f      // multi-character key label (ABC / 123 / #+=)
    private var emojiTextSize = 0f

    /** Cache all view-side prefs (size mode, layout, key visibility, Auto-Period). Idempotent;
     *  called on init and on every reset(), so settings changes take effect next time the keyboard opens. */
    private fun applyPrefs() {
        val height = Prefs.keyHeight(context)
        // A height change makes the px-stored learned offsets stale, so reset them to the prior. Guarded
        // on appliedHeight != null because the first applyPrefs() runs from init{}, before learnedBiasY /
        // rowBiasPrior (declared lower in the file) are initialized.
        if (appliedHeight != null && appliedHeight != height) {
            for (i in learnedBiasY.indices) learnedBiasY[i] = rowBiasPrior[i]
            Prefs.setTouchOffsets(context, "")
        }
        appliedHeight = height
        compact = height == Prefs.HEIGHT_SHORT
        // Read before the metrics below, which size the suggestion strip from it.
        suggestionsOn = Prefs.suggestions(context)
        when (height) {
            Prefs.HEIGHT_SHORT -> {
                padTop = dpf(4); padBottom = dpf(5); padSide = dpf(4)
                keyGap = dpf(2); rowKeyH = dpf(32)
                keyTextSize = spf(20); labelTextSize = spf(15); emojiTextSize = spf(24)
            }
            Prefs.HEIGHT_TALL -> {
                padTop = dpf(10); padBottom = dpf(12); padSide = dpf(6)
                keyGap = dpf(3); rowKeyH = dpf(58)
                keyTextSize = spf(30); labelTextSize = spf(20); emojiTextSize = spf(32)
            }
            else -> {   // HEIGHT_MEDIUM (default)
                padTop = dpf(8); padBottom = dpf(10); padSide = dpf(6)
                keyGap = dpf(3); rowKeyH = dpf(48)
                keyTextSize = spf(26); labelTextSize = spf(18); emojiTextSize = spf(30)
            }
        }
        rowPitch = rowKeyH + keyGap * 2
        // Deliberately small — about half a key. It is a glance target, not a row of buttons, and the
        // keyboard is a clone of a design that has no suggestion bar at all, so the less of one it adds
        // the better. Off by default for the same reason.
        stripTextSize = spf(if (compact) 11 else 12)
        stripH = if (suggestionsOn) dpf(if (compact) 20 else 24) else 0f

        keyLayout = Prefs.keyLayout(context)
        autoPeriod = Prefs.autoPeriod(context)
        swipeTyping = Prefs.swipeTyping(context)
        hiddenKeys.clear()
        if (!Prefs.voiceEnabled(context)) hiddenKeys.add(Key.MIC)
        if (!Prefs.emojiKey(context)) hiddenKeys.add(Key.EMOJI)
        if (!Prefs.returnKey(context)) hiddenKeys.add(Key.ENTER)
    }

    private val emojiCols = 8
    private val emojiRowCount = (Layout.emoji.size + emojiCols - 1) / emojiCols  // 24 / 8 = 3

    // --- paints / icon cache ---
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dpf(3)
        strokeCap = Paint.Cap.ROUND
    }
    private val iconCache = HashMap<Int, Drawable>()

    // --- touch tracking ---
    private val pressed = HashMap<Int, PlacedKey>()   // pointerId -> key, for the pressed highlight
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L   // ACTION_DOWN's event time, for the dismiss hold in onTouchEvent
    private var firstPointerId = -1
    private var firstKeyRetractable = false   // did the gesture's first tap commit a retractable char?
    private var dismissedThisGesture = false
    private var velocityTracker: VelocityTracker? = null   // for early swipe-down (dismiss) detection

    // --- swipe typing ---
    // The trace is collected in key units, in fixed-size arrays: a gesture is a stream of MotionEvents
    // arriving every few milliseconds, and allocating on each one is exactly the wrong thing to do
    // while the user is mid-word. A trace longer than the cap simply stops recording — by then there
    // is far more shape than the 32-sample decoder can use.
    private val traceX = FloatArray(MAX_TRACE_POINTS)
    private val traceY = FloatArray(MAX_TRACE_POINTS)
    private var traceCount = 0
    private var tracing = false
    /** Pixel copy of the trace, for drawing the trail. Same indices as [traceX]/[traceY]. */
    private val trailX = FloatArray(MAX_TRACE_POINTS)
    private val trailY = FloatArray(MAX_TRACE_POINTS)
    /** Width of a letter key (px). The x half of the key-unit conversion; y uses [rowPitch]. */
    private var letterKeyW = 1f

    // --- suggestion strip ---
    // Three slots above the top row, present only when the setting is on. Kept out of [placed] and
    // hit-tested separately: the key rects deliberately tile the whole surface with no gaps, and
    // threading a fourth kind of cell through that would put dead zones between the strip and the keys.
    private var suggestions: List<StripItem> = emptyList()
    private var pressedSuggestion = -1
    /** Height of the strip, or 0 when it isn't shown. Everything below shifts down by this. */
    private var stripH = 0f
    private var stripTextSize = 0f

    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
        applyPrefs()
        rebuild()
        // Note: loadLearnedOffsets() runs in onAttachedToWindow, not here — the offset fields are
        // declared lower in the file, so they aren't initialized yet during this init block.
    }

    private val currentRows: List<List<String>>
        get() {
            val rows = when (layer) {
                Layer.LETTERS -> when (keyLayout) {
                    Prefs.LAYOUT_AZERTY -> Layout.azerty
                    Prefs.LAYOUT_QWERTZ -> Layout.qwertz
                    else -> Layout.letters
                }
                Layer.SYMBOLS -> Layout.symbols
                Layer.MORE -> Layout.more
                Layer.EMOJI -> emptyList()
            }
            // Drop any control keys turned off in settings (mic / emoji / return); the row reflows.
            return if (hiddenKeys.isEmpty()) rows else rows.map { row -> row.filter { it !in hiddenKeys } }
        }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        // Emoji has 3 glyph rows + 1 back-chevron row = 4, same pitch as the letter layers, so the
        // keyboard keeps a constant height and doesn't jump when you switch to emoji.
        val rowCount = when {
            listening -> Layout.letters.size           // keep height constant while listening
            layer == Layer.EMOJI -> emojiRowCount + 1
            else -> currentRows.size
        }
        val h = stripTop + padTop + rowCount * rowPitch + padBottom
        setMeasuredDimension(w, h.toInt())
    }

    /**
     * Where the key rows start. Zero while dictating: that surface draws over the whole view and has no
     * word to suggest anything about.
     */
    private val stripTop: Float get() = if (listening) 0f else stripH

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun rebuild() {
        relayout()
        requestLayout()
        invalidate()
    }

    private fun relayout() {
        placed.clear()
        letterKeys.clear()
        if (width == 0 || height == 0 || listening) return
        if (layer == Layer.EMOJI) { layoutEmoji(); return }

        val w = width.toFloat()
        val h = height.toFloat()
        val rows = currentRows
        val n = rows.size
        // The strip sits above everything, so the key bands start below it. The top row's band still
        // absorbs the top padding, it just no longer reaches y=0 — the strip owns that space.
        val top = stripTop
        for (i in rows.indices) {
            // Bands tile [top, h]: the top row absorbs the top pad, the bottom row absorbs the bottom pad.
            val bandTop = if (i == 0) top else top + padTop + i * rowPitch
            val bandBottom = if (i == n - 1) h else top + padTop + (i + 1) * rowPitch
            val visTop = top + padTop + i * rowPitch + keyGap
            val visBottom = visTop + rowKeyH
            layoutRow(rows[i], bandTop, bandBottom, visTop, visBottom, w)
        }
        for (k in placed) if (isLetter(k.id)) letterKeys.add(k)
        publishKeyGrid()
    }

    /**
     * Hand the host the a-z key centres in key units, so the corrector and the swipe decoder score
     * against the geometry that is actually on screen. This is what makes AZERTY / QWERTZ and the
     * three height presets work with no per-layout tables anywhere in the text logic.
     */
    private fun publishKeyGrid() {
        if (letterKeys.isEmpty()) return
        letterKeyW = letterKeys[0].vis.width().coerceAtLeast(1f)
        val positions = letterKeys.map { Triple(it.id[0], it.cx / letterKeyW, (it.cy - stripH) / rowPitch) }
        listener?.onKeyGrid(KeyGrid.of(positions))
    }

    private fun layoutRow(
        row: List<String>, bandTop: Float, bandBottom: Float,
        visTop: Float, visBottom: Float, w: Float,
    ) {
        val totalWeight = row.sumOf { weightFor(it).toDouble() }.toFloat()
        val drawLeft = padSide
        val drawW = w - padSide * 2
        var cum = 0f
        for ((j, id) in row.withIndex()) {
            val cellLeft = drawLeft + drawW * (cum / totalWeight)
            cum += weightFor(id)
            val cellRight = drawLeft + drawW * (cum / totalWeight)
            // Hit rects tile [0, w]: edge keys reach the screen edge; interior boundaries sit on the
            // visible cell edge, i.e. the midline of the gutter between two keys (nearest-key by design).
            val hitLeft = if (j == 0) 0f else cellLeft
            val hitRight = if (j == row.size - 1) w else cellRight
            placed.add(
                PlacedKey(
                    id,
                    RectF(hitLeft, bandTop, hitRight, bandBottom),
                    RectF(cellLeft + keyGap, visTop, cellRight - keyGap, visBottom),
                ),
            )
        }
    }

    private fun layoutEmoji() {
        // Same vertical metrics as the letter layers (padTop / rowPitch / rowKeyH), so the emoji panel
        // is the exact same height and switching layers never resizes the keyboard.
        val w = width.toFloat()
        val h = height.toFloat()
        val drawW = w - padSide * 2
        val rows = Layout.emoji.chunked(emojiCols)
        val top = stripTop
        for (i in rows.indices) {
            val bandTop = if (i == 0) top else top + padTop + i * rowPitch
            val bandBottom = top + padTop + (i + 1) * rowPitch
            val visTop = top + padTop + i * rowPitch + keyGap
            val visBottom = visTop + rowKeyH
            rows[i].forEachIndexed { j, glyph ->
                val cellLeft = padSide + drawW * (j.toFloat() / emojiCols)
                val cellRight = padSide + drawW * ((j + 1).toFloat() / emojiCols)
                val hitLeft = if (j == 0) 0f else cellLeft
                val hitRight = if (j == emojiCols - 1) w else cellRight
                placed.add(
                    PlacedKey(
                        glyph,
                        RectF(hitLeft, bandTop, hitRight, bandBottom),
                        RectF(cellLeft, visTop, cellRight, visBottom),
                    ),
                )
            }
        }
        // Back-to-letters chevron: its own row band (hit spans the full width), drawn as a centered chevron.
        val backTop = top + padTop + rows.size * rowPitch
        val boxW = dpf(64)
        val boxH = rowKeyH
        val cx = w / 2f
        val cy = backTop + keyGap + rowKeyH / 2f
        placed.add(
            PlacedKey(
                Key.EMOJI_BACK,
                RectF(0f, backTop, w, h),
                RectF(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f),
            ),
        )
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (listening) { drawListening(canvas); return }
        for (pk in placed) {
            if (pressed.containsValue(pk)) {
                val r = dpf(8)
                canvas.drawRoundRect(pk.vis, r, r, pressPaint)
            }
            drawKey(canvas, pk)
        }
        if (stripH > 0f) drawStrip(canvas)
        if (tracing) drawTrail(canvas)
    }

    /**
     * The suggestion strip: up to three words in small type, split by faint vertical rules.
     *
     * Empty slots are left blank rather than the strip being hidden, because a strip that appears and
     * disappears would resize the keyboard under the user's thumb mid-sentence — every key would move
     * as soon as a word became suggestible. Its height is fixed for as long as the setting is on.
     */
    private fun drawStrip(canvas: Canvas) {
        val slotW = width / Suggester.SLOTS.toFloat()
        if (pressedSuggestion in 0 until Suggester.SLOTS && suggestionAt(pressedSuggestion) != null) {
            canvas.drawRect(
                pressedSuggestion * slotW, 0f, (pressedSuggestion + 1) * slotW, stripH, pressPaint,
            )
        }
        textPaint.textSize = stripTextSize
        val baseline = stripH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        for (i in 0 until Suggester.SLOTS) {
            val item = suggestionAt(i) ?: continue
            val cx = (i + 0.5f) * slotW
            // Quotes mark the keep-as-typed slot, the same signal iOS uses. Straight quotes rather
            // than typographic ones: the strip renders in the Light SDK's font and a missing glyph
            // would show as tofu right where the user is being asked to trust the word.
            val label = if (item.literal) "\"${item.word}\"" else item.word
            // Ellipsise rather than overflow into the neighbouring slot.
            canvas.drawText(fitToWidth(label, slotW - dpf(10)), cx, baseline, textPaint)
        }
        // Dividers between occupied slots only, so an empty strip is just empty.
        for (i in 1 until Suggester.SLOTS) {
            if (suggestionAt(i - 1) == null && suggestionAt(i) == null) continue
            val x = i * slotW
            canvas.drawRect(x - dpf(0.5f), stripH * 0.25f, x + dpf(0.5f), stripH * 0.75f, dividerPaint)
        }
    }

    private fun suggestionAt(i: Int): StripItem? =
        suggestions.getOrNull(i)?.takeIf { it.word.isNotEmpty() }

    /** [text], truncated with an ellipsis until it fits [maxWidth] at the current [textPaint] size. */
    private fun fitToWidth(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && textPaint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    /**
     * The swipe-typing trail. It exists only while a finger is down and vanishes the instant the word
     * commits, so the keyboard at rest is unchanged — but without it a swipe gives no sign it was
     * understood as anything but a mis-tap, which reads as the keyboard being broken. Thin and grey
     * rather than a bright ribbon, to sit inside the LightOS palette.
     */
    private fun drawTrail(canvas: Canvas) {
        if (traceCount < 2) return
        for (i in 1 until traceCount) {
            canvas.drawLine(trailX[i - 1], trailY[i - 1], trailX[i], trailY[i], trailPaint)
        }
    }

    /** The voice-dictation surface: a big centered mic, the live status/partial text, and a hint. */
    private fun drawListening(canvas: Canvas) {
        val cx = width / 2f
        val midY = height / 2f
        val d = iconCache.getOrPut(R.drawable.ic_kb_mic) { context.getDrawable(R.drawable.ic_kb_mic)!! }
        val size = dpf(44)
        val left = (cx - size / 2f).toInt()
        val top = (midY - size - dpf(6)).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
        textPaint.textSize = spf(18)
        // Wrap the live text so a long phrase stacks into lines instead of running off the screen.
        drawWrappedCentered(canvas, listeningStatus, cx, midY + dpf(22), width - dpf(48), textPaint)
        textPaint.textSize = spf(12)
        canvas.drawText("Tap when done", cx, height - dpf(18), textPaint)
    }

    /** Draw [text] centered on ([cx],[centerY]), wrapping at word boundaries to fit [maxWidth]. */
    private fun drawWrappedCentered(
        canvas: Canvas, text: String, cx: Float, centerY: Float, maxWidth: Float, paint: Paint,
    ) {
        if (text.isEmpty()) return
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                lines.add(line); line = word
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        val lineH = paint.descent() - paint.ascent()
        var baseline = centerY - lines.size * lineH / 2f - paint.ascent()
        for (l in lines) { canvas.drawText(l, cx, baseline, paint); baseline += lineH }
    }

    private fun drawKey(canvas: Canvas, pk: PlacedKey) {
        val id = pk.id
        if (id == Key.SPACE) {
            val y = pk.vis.centerY()
            canvas.drawRect(pk.vis.left + dpf(28), y - dpf(1), pk.vis.right - dpf(28), y + dpf(1), spacePaint)
            return
        }
        val icon = iconFor(id)
        if (icon != null) {
            drawIcon(canvas, icon, pk.vis, padFor(id))
            // Caps-lock indicator: an underline beneath the shift glyph.
            if (id == Key.SHIFT && capsLock) {
                val cx = pk.vis.centerX()
                val y = pk.vis.centerY() + dpf(11)
                canvas.drawRect(cx - dpf(7), y - dpf(1), cx + dpf(7), y + dpf(1), spacePaint)
            }
            return
        }
        val size = if (layer == Layer.EMOJI) emojiTextSize else if (id.length == 1) keyTextSize else labelTextSize
        textPaint.textSize = size
        val baseline = pk.vis.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(labelFor(id), pk.vis.centerX(), baseline, textPaint)
    }

    private fun drawIcon(canvas: Canvas, res: Int, vis: RectF, pad: Float) {
        val d = iconCache.getOrPut(res) { context.getDrawable(res)!! }
        val size = (minOf(vis.width(), vis.height()) - pad * 2).coerceAtLeast(1f)
        val left = (vis.centerX() - size / 2f).toInt()
        val top = (vis.centerY() - size / 2f).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
    }

    private fun iconFor(id: String): Int? = when (id) {
        Key.EMOJI -> R.drawable.ic_kb_emoji
        Key.BACKSPACE -> R.drawable.ic_kb_backspace
        Key.ENTER -> R.drawable.ic_kb_enter
        Key.EMOJI_BACK -> R.drawable.ic_kb_chevron_down
        Key.MIC -> R.drawable.ic_kb_mic
        Key.SHIFT -> if (shifted) R.drawable.ic_kb_chevron_down else R.drawable.ic_kb_chevron_up
        else -> null
    }

    // Icon inset inside its key. Compact keys are shorter, so the insets shrink too or the glyphs vanish.
    private fun padFor(id: String): Float = when (id) {
        Key.SHIFT -> if (compact) dpf(6) else dpf(9)
        Key.BACKSPACE, Key.EMOJI_BACK -> if (compact) dpf(7) else dpf(10)
        Key.MIC -> if (compact) dpf(6) else dpf(9)
        else -> if (compact) dpf(5) else dpf(7)
    }

    private fun labelFor(id: String): String =
        if (shifted && layer == Layer.LETTERS && id.length == 1 && id[0].isLetter()) id.uppercase() else id

    private fun weightFor(id: String): Float = when (id) {
        Key.SPACE -> 5f
        Key.SYMBOLS, Key.LETTERS, Key.MORE -> 1.4f
        else -> 1f
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (listening) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { tap(); listener?.onMicCancel() }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                dismissedThisGesture = false
                firstPointerId = ev.getPointerId(0)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                // The strip commits on UP, not on DOWN like the keys do. A key commits on down because
                // that is what makes fast typing feel immediate and stops letters being dropped when a
                // finger rolls off; a suggestion is a deliberate, one-off tap where the cost of getting
                // it wrong is a whole word, so it gets the chance to be cancelled by sliding off.
                val slot = suggestionSlotAt(ev.x, ev.y)
                if (slot >= 0) {
                    pressedSuggestion = slot
                    firstKeyRetractable = false
                    armSuggestionHold()
                    invalidate()
                    return true
                }
                firstKeyRetractable = pressDown(firstPointerId, ev.x, ev.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Ignored mid-trace: a second finger landing while a word is being drawn is a palm or
                // a stray thumb, and typing its letter would corrupt the word about to be committed.
                if (!tracing) {
                    val idx = ev.actionIndex
                    pressDown(ev.getPointerId(idx), ev.getX(idx), ev.getY(idx))
                }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (pressedSuggestion >= 0) {
                    // Slide off the slot and the tap is abandoned, the usual button behaviour.
                    val still = suggestionSlotAt(ev.x, ev.y)
                    if (still != pressedSuggestion) {
                        pressedSuggestion = -1
                        removeCallbacks(suggestionHold)
                        invalidate()
                    }
                    return true
                }
                if (suggestionForgotten) return true   // the hold already acted; ignore the rest of it
                val idx = ev.findPointerIndex(firstPointerId)
                if (idx >= 0 && !dismissedThisGesture) {
                    val x = ev.getX(idx)
                    val y = ev.getY(idx)
                    if (!tracing) {
                        val dy = y - downY
                        val dx = x - downX
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vy = velocityTracker?.getYVelocity(firstPointerId) ?: 0f
                        // A short downward drag (30dp) OR a quick downward flick both count, as long as
                        // the motion is clearly vertical — but only once [held] says the finger paused
                        // first. Swipe typing starts moving within a few milliseconds of touch-down; a
                        // deliberate dismiss doesn't, so gating on elapsed time rather than reordering
                        // the checks is what keeps a fast "no" or "on" trace from racing this branch and
                        // losing the race depending on how the touch samples happened to land.
                        val verticalDrag = dy > abs(dx) * 1.5f
                        val held = ev.eventTime - downTime >= DISMISS_HOLD_MS
                        if (held && verticalDrag && (dy > dpf(30) || (vy > dpf(900) && dy > dpf(14)))) {
                            dismissedThisGesture = true
                            stopBackspaceRepeat()
                            // The first tap already committed a char on down; retract it so the swipe
                            // doesn't leave a stray letter behind.
                            if (firstKeyRetractable) listener?.onBackspace()
                            pressed.clear()
                            invalidate()
                            listener?.onDismiss()
                        } else {
                            maybeStartTrace(x, y)
                        }
                    }
                    if (tracing) addTracePoint(x, y)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pid = ev.getPointerId(ev.actionIndex)
                pressed.remove(pid)
                if (pid == backspacePointerId) stopBackspaceRepeat()
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                val slot = pressedSuggestion
                pressedSuggestion = -1
                pressed.clear()
                stopBackspaceRepeat()
                removeCallbacks(suggestionHold)
                velocityTracker?.recycle()
                velocityTracker = null
                if (suggestionForgotten) { suggestionForgotten = false; invalidate(); return true }
                if (slot >= 0) {
                    val item = suggestionAt(slot)
                    invalidate()
                    if (item != null) { tap(); listener?.onSuggestion(item) }
                    return true
                }
                // The lift point is recorded before the trace is handed over: MOVE events stop arriving a
                // frame before the finger leaves the glass, and on a two-key word the last key IS half
                // the word — losing the end of the stroke there is losing the letter.
                if (tracing) { addTracePoint(ev.x, ev.y); finishTrace() } else invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedSuggestion = -1
                suggestionForgotten = false
                pressed.clear()
                stopBackspaceRepeat()
                removeCallbacks(suggestionHold)
                velocityTracker?.recycle()
                velocityTracker = null
                abandonTrace()   // the window took the gesture away; don't guess a word from half of it
                invalidate()
            }
        }
        return true
    }

    /** Resolve the key under a pointer, commit it immediately, and light it up. */
    private fun pressDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (dismissedThisGesture) return false
        val raw = findKey(x, y) ?: return false
        // Only letters get the accuracy treatment; control keys & other layers stay exact hit-testing.
        val key = if (layer == Layer.LETTERS && isLetter(raw.id)) resolveLetter(x, y, raw) else raw
        pressed[pointerId] = key
        invalidate()
        val retractable = onKey(key.id)
        if (key.id == Key.BACKSPACE) {           // first delete fired on down; now arm the repeat
            backspacePointerId = pointerId
            backspaceDownMs = System.currentTimeMillis()
            removeCallbacks(backspaceRepeat)
            postDelayed(backspaceRepeat, BACKSPACE_INITIAL_DELAY_MS)
        }
        return retractable
    }

    private fun stopBackspaceRepeat() {
        backspacePointerId = -1
        removeCallbacks(backspaceRepeat)
    }

    private fun armSuggestionHold() {
        suggestionForgotten = false
        removeCallbacks(suggestionHold)
        postDelayed(suggestionHold, SUGGESTION_HOLD_MS)
    }

    // ------------------------------------------------------------------ swipe typing

    /**
     * Promote an in-progress drag to a word trace, if it looks like one. Three conditions, and all
     * three matter:
     *
     *  - it began on a letter (so dragging off `123` or the space bar still does nothing);
     *  - the finger has left the key it started on — a tap with a shaky finger must never become a
     *    gesture, because that would eat ordinary typing;
     *  - only one finger is down, since rolling two keys at once is fast typing, not a trace.
     *
     * The letter the starting key committed on touch-down is retracted here: it was the gesture's
     * first letter, and the decoded word will supply it.
     */
    private fun maybeStartTrace(x: Float, y: Float) {
        if (!swipeTyping || layer != Layer.LETTERS || letterKeys.isEmpty()) return
        if (pressed.size != 1) return
        val start = pressed[firstPointerId] ?: return
        if (!isLetter(start.id)) return
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy < traceStartDist * traceStartDist) return
        if (start.hit.contains(x.coerceIn(0f, width - 1f), y.coerceIn(0f, height - 1f))) return

        tracing = true
        stopBackspaceRepeat()
        if (firstKeyRetractable) listener?.onBackspace()
        firstKeyRetractable = false
        pressed.clear()
        traceCount = 0
        addTracePoint(downX, downY)
        addTracePoint(x, y)
    }

    /**
     * Record one traced point, in pixels for the trail and in key units for the decoder. Points closer
     * than [traceMinStep] to the last one are dropped: the digitiser reports far more samples than the
     * decoder can use, and a cluster of them where the finger slowed down would drag the decoder's
     * equal-spacing resample toward the pause and distort the stroke.
     */
    private fun addTracePoint(x: Float, y: Float) {
        if (traceCount >= MAX_TRACE_POINTS) return
        if (traceCount > 0) {
            val dx = x - trailX[traceCount - 1]
            val dy = y - trailY[traceCount - 1]
            if (dx * dx + dy * dy < traceMinStep * traceMinStep) return
        }
        trailX[traceCount] = x
        trailY[traceCount] = y
        traceX[traceCount] = x / letterKeyW
        // Same upward parallax correction the tap model applies (fingers register low). One averaged
        // offset rather than the per-row values, since a trace crosses rows by definition.
        // Measured from the top of the first key row, not the top of the view, so the key-unit
        // coordinates keep matching the grid published by publishKeyGrid whether the strip is shown
        // or not — otherwise turning suggestions on would silently shift every gesture down by a row.
        traceY[traceCount] = (y - stripH + averageBiasY()) / rowPitch
        traceCount++
        invalidate()
    }

    private fun averageBiasY(): Float {
        var sum = 0f
        for (v in learnedBiasY) sum += v
        return sum / learnedBiasY.size
    }

    /**
     * Hand the finished trace to the host for decoding. [traceX]/[traceY] are passed as-is rather than
     * copied — [Listener.onGesture] decodes synchronously and must not retain them.
     */
    private fun finishTrace() {
        tracing = false
        val n = traceCount
        traceCount = 0
        invalidate()
        // Two points is a real stroke, not a stub: the trace only starts once the finger has left the
        // key it went down on, and a short flick between neighbouring keys can report exactly one MOVE
        // before the lift. Demanding three threw those away — every one of them a two-letter word.
        if (n >= 2) listener?.onGesture(traceX, traceY, n)
    }

    private fun abandonTrace() {
        tracing = false
        traceCount = 0
    }

    /** Which strip slot ([0, SLOTS)) a touch lands in, or -1 if it isn't on the strip at all. */
    private fun suggestionSlotAt(x: Float, y: Float): Int {
        if (stripH <= 0f || listening || y >= stripH) return -1
        val slot = (x / (width / Suggester.SLOTS.toFloat())).toInt()
        return slot.coerceIn(0, Suggester.SLOTS - 1)
    }

    /** Tiled rects always contain the point; the nearest-center fallback only covers off-surface taps. */
    private fun findKey(x: Float, y: Float): PlacedKey? {
        if (placed.isEmpty()) return null
        val cx = x.coerceIn(0f, width - 1f)
        val cy = y.coerceIn(0f, height - 1f)
        placed.firstOrNull { it.hit.contains(cx, cy) }?.let { return it }
        return placed.minByOrNull { val dx = it.cx - cx; val dy = it.cy - cy; dx * dx + dy * dy }
    }

    // ------------------------------------------------------------------ typing accuracy
    //
    // Per-tap key selection = spatial likelihood (Gaussian on distance) × language likelihood
    // (a character trigram model, frequency-weighted English). For an ambiguous tap near a key
    // boundary this lets context break the tie (after "th", a tap between e/r/w resolves to "e").
    // A confident tap inside a key's core is returned directly, so deliberate taps are never
    // overridden. Distances are normalised by key width / row pitch so both axes are comparable.
    //
    // Two refinements from the touch-modelling literature:
    //   - The spatial Gaussian width has a fixed finger-size floor (FFitts / dual-Gaussian model,
    //     Bi/Li/Zhai CHI'13): touch scatter = a size-proportional part PLUS a ~constant ≈finger-width
    //     part. On small keys the floor dominates, so the short compact rows widen the spatial term on
    //     their own and let the language model carry more of the tap — no per-mode tuning needed.
    //   - The touch point is shifted up by a *per-row* offset (the "perceived input point" parallax —
    //     Holz & Baudisch; per-row because finger pitch varies by row, Henze et al.) that is learned
    //     per-user from the typist's own taps (see learnOffset / rowBiasPrior).

    /** Holds ln P(c3 | c1,c2) for the 27-symbol alphabet (a-z + word boundary). */
    private class CharModel(private val logp: FloatArray) {
        fun lp(c1: Int, c2: Int, c3: Int): Float = logp[(c1 * SYMS + c2) * SYMS + c3]
        companion object { const val SYMS = 27; const val BOUNDARY = 26 }
    }

    private val charModel: CharModel? by lazy { loadCharModel() }

    // Tunables. lambda scales how much context can sway a tap; sigmaFrac/sigmaAbs set the spatial
    // Gaussian width (see resolveLetter). If a particular zone reads wrong, nudge these.
    private val biasX = 0f
    private val coreFrac = 0.5f       // within this fraction of a key (normalised) → no override
    private val sigmaFrac = 0.72f     // size-proportional Gaussian width, in key units
    private val sigmaAbs = dpf(11)    // fixed finger-size floor (≈1.7 mm), added in quadrature (FFitts)
    private val radiusFrac = 1.5f     // only score candidates within this many key units
    private val lambda = 1.0f

    // Per-row vertical touch offset (px). Fingers land low — the "perceived input point" parallax: you
    // aim with the top of your finger but the screen senses the contact patch lower down (Holz &
    // Baudisch). The undershoot also varies by row, since finger pitch differs top-to-bottom (Henze
    // et al.). Negative shifts the effective point up. [0] = top (qwerty) … [2] = bottom (zxcv).
    //
    // (a) rowBiasPrior is the starting guess for a fresh user. (b) learnedBiasY adapts it per-user from
    // their own taps (see learnOffset) and is persisted — the parallax offset is strongly individual
    // (Holz & Baudisch; Weir et al.), so the personal value is where the real accuracy gain is.
    private val rowBiasPrior = floatArrayOf(-dpf(10), -dpf(8), -dpf(6))
    private val learnedBiasY = rowBiasPrior.copyOf()   // safe default = the prior; loadLearnedOffsets refines
    private val offsetLearnRate = 0.06f   // EMA step per tap; slow, so a few stray taps don't sway it

    private fun isLetter(id: String): Boolean = id.length == 1 && id[0] in 'a'..'z'

    private fun resolveLetter(x: Float, y: Float, raw: PlacedKey): PlacedKey {
        if (letterKeys.isEmpty()) return raw
        val result = resolveLetterTo(x, y, raw)
        learnOffset(y, result)   // passively adapt the per-row offset from this tap
        return result
    }

    /** The spatial × language resolution; [resolveLetter] wraps it to also learn the touch offset. */
    private fun resolveLetterTo(x: Float, y: Float, raw: PlacedKey): PlacedKey {
        val cx = x + biasX
        val cy = y + learnedBiasY[rowOf(raw)]
        val kw = raw.vis.width().coerceAtLeast(1f)
        // nearest letter key, in normalised (per-axis) distance
        var nearest = raw
        var nd2 = Float.MAX_VALUE
        for (k in letterKeys) {
            val d2 = norm2(k, cx, cy, kw)
            if (d2 < nd2) { nd2 = d2; nearest = k }
        }
        if (sqrt(nd2) < coreFrac) return nearest          // confident tap — leave it alone
        val model = charModel ?: return nearest
        val (c1, c2) = contextSymbols()
        // Per-axis Gaussian widths (key units): the size-proportional sigmaFrac combined in quadrature
        // with the fixed sigmaAbs floor. The floor is a constant in px, so on the short compact rows it
        // grows as a fraction of the row pitch — widening the vertical term and ceding more to context.
        val twoSx2 = 2f * sigmaKeyUnits(kw).let { it * it }
        val twoSy2 = 2f * sigmaKeyUnits(rowPitch).let { it * it }
        val radius2 = radiusFrac * radiusFrac
        var best = nearest
        var bestScore = -Float.MAX_VALUE
        for (k in letterKeys) {
            val dx = (k.cx - cx) / kw
            val dy = (k.cy - cy) / rowPitch
            if (dx * dx + dy * dy > radius2) continue
            val score = -dx * dx / twoSx2 - dy * dy / twoSy2 + lambda * model.lp(c1, c2, k.id[0] - 'a')
            if (score > bestScore) { bestScore = score; best = k }
        }
        return best
    }

    /** Gaussian σ along one axis, in normalised key-units: the size-proportional [sigmaFrac] combined
     *  in quadrature with the fixed px floor [sigmaAbs] (expressed in key-units via the axis [pitchPx]). */
    private fun sigmaKeyUnits(pitchPx: Float): Float {
        val floor = sigmaAbs / pitchPx
        return sqrt(sigmaFrac * sigmaFrac + floor * floor)
    }

    /** Which letter row a key sits in (0 = top … 2 = bottom), from its centre. */
    private fun rowOf(key: PlacedKey): Int =
        ((key.cy - stripH - padTop) / rowPitch).toInt().coerceIn(0, learnedBiasY.size - 1)

    /** Passively learn the per-row vertical offset: nudge the resolved key's row toward centring this
     *  tap. Skip taps where the language model overrode a far spatial pick (the intended position is
     *  unreliable there), and clamp, so a few stray taps can never run the offset away. */
    private fun learnOffset(rawY: Float, key: PlacedKey) {
        val row = rowOf(key)
        val delta = rawY - key.cy                                      // + if the tap landed low
        if (abs(delta + learnedBiasY[row]) > 0.6f * rowPitch) return   // not a clean same-key tap
        learnedBiasY[row] += offsetLearnRate * (-delta - learnedBiasY[row])
        learnedBiasY[row] = learnedBiasY[row].coerceIn(-dpf(24), dpf(2))   // ≈3.8 mm of upward headroom
    }

    /** Restore the learned offsets (px), or fall back to the prior for a fresh install. */
    private fun loadLearnedOffsets() {
        val saved = Prefs.touchOffsets(context)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        for (i in learnedBiasY.indices) learnedBiasY[i] = saved?.getOrNull(i) ?: rowBiasPrior[i]
    }

    private fun saveLearnedOffsets() {
        Prefs.setTouchOffsets(context, learnedBiasY.joinToString(",") { it.toString() })
    }

    private fun norm2(k: PlacedKey, cx: Float, cy: Float, kw: Float): Float {
        val dx = (k.cx - cx) / kw
        val dy = (k.cy - cy) / rowPitch
        return dx * dx + dy * dy
    }

    /** The two symbols before the cursor (a-z → 0..25, anything else / absent → boundary). */
    private fun contextSymbols(): Pair<Int, Int> {
        val s = listener?.textBeforeCursor(2)?.toString().orEmpty()
        val c1 = if (s.length >= 2) symIndex(s[s.length - 2]) else CharModel.BOUNDARY
        val c2 = if (s.isNotEmpty()) symIndex(s[s.length - 1]) else CharModel.BOUNDARY
        return c1 to c2
    }

    private fun symIndex(ch: Char): Int {
        val l = ch.lowercaseChar()
        return if (l in 'a'..'z') l - 'a' else CharModel.BOUNDARY
    }

    private fun loadCharModel(): CharModel? = try {
        val bytes = resources.openRawResource(R.raw.charmodel).use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        CharModel(arr)
    } catch (e: Exception) {
        null   // fall back to pure nearest-key (spatial only) if the asset is missing/corrupt
    }

    /** Applies a key. Returns true if it committed a single retractable character (text or space). */
    private fun onKey(id: String): Boolean {
        tap()
        if (id != Key.SPACE) lastSpaceTapMs = 0L   // any other key breaks a pending double-space
        when (id) {
            Key.SHIFT -> { onShift(); rebuild() }
            Key.BACKSPACE -> listener?.onBackspace()
            Key.ENTER -> listener?.onEnter()
            Key.EMOJI -> { layer = Layer.EMOJI; rebuild() }
            Key.EMOJI_BACK -> { layer = Layer.LETTERS; rebuild() }
            Key.SYMBOLS -> { layer = Layer.SYMBOLS; rebuild() }
            Key.MORE -> { layer = Layer.MORE; rebuild() }
            Key.LETTERS -> { layer = Layer.LETTERS; rebuild() }
            Key.MIC -> listener?.onMic()
            Key.SPACE -> {
                val now = System.currentTimeMillis()
                val doublePeriod = autoPeriod && now - lastSpaceTapMs < DOUBLE_TAP_MS
                lastSpaceTapMs = if (doublePeriod) 0L else now   // consume, so a 3rd tap starts fresh
                if (doublePeriod) { listener?.onDoubleSpace(); return false }
                listener?.onText(" "); return true
            }
            else -> {
                if (layer == Layer.EMOJI) { listener?.onText(id); return false }
                listener?.onText(labelFor(id))
                return true
            }
        }
        return false
    }

    /** Shift tap: toggles one-shot uppercase; a quick double-tap latches caps lock; tapping while
     *  locked clears it. */
    private fun onShift() {
        val now = System.currentTimeMillis()
        when {
            capsLock -> { capsLock = false; shifted = false }
            now - lastShiftTapMs < DOUBLE_TAP_MS -> { capsLock = true; shifted = true }
            else -> shifted = !shifted
        }
        lastShiftTapMs = now
    }

    /** Reset for a newly focused field: open on letters, or the numbers layer when [numeric] (number /
     *  phone / date fields). Also re-reads prefs, so toggling compact / layout / key visibility in
     *  settings takes effect next time the keyboard opens. Initial uppercase follows Auto-Capitalize
     *  (the IME's updateShift refines it immediately). */
    fun reset(numeric: Boolean = false) {
        stopBackspaceRepeat()
        abandonTrace()
        suggestions = emptyList()
        pressedSuggestion = -1
        suggestionForgotten = false
        removeCallbacks(suggestionHold)
        saveLearnedOffsets()   // persist what we learned in the field we're leaving
        applyPrefs()
        // Number / phone / date fields open straight on the symbols layer (its top row is 1-0).
        layer = if (numeric) Layer.SYMBOLS else Layer.LETTERS
        shifted = Prefs.autoCapitalize(context)
        capsLock = false; listening = false; rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadLearnedOffsets()   // safe here: construction is complete, so the offset fields exist
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        saveLearnedOffsets()
        super.onDetachedFromWindow()
    }

    /** Replace the strip's contents. Fewer than three items leaves the remaining slots blank. */
    fun setSuggestions(items: List<StripItem>) {
        if (stripH <= 0f) return
        if (items == suggestions) return
        suggestions = items
        pressedSuggestion = -1
        invalidate()
    }

    /** Enter the voice-dictation listening surface. */
    fun startListeningUi() { listening = true; listeningStatus = "Listening…"; rebuild() }

    /** Update the listening status / live partial transcription. */
    fun setListeningStatus(text: String) {
        if (!listening) return
        listeningStatus = text
        invalidate()
    }

    /** Leave the listening surface, back to keys. */
    fun stopListeningUi() {
        if (!listening) return
        listening = false
        rebuild()
    }

    /**
     * Sentence-case auto-shift, driven by the host IME from the field's caps mode: uppercase at a
     * sentence start, lowercase after the first letter. One-shot — a manual SHIFT tap holds only
     * until the next letter, after which the IME recomputes this.
     */
    /** Whether the next letter would be typed uppercase — the swipe decoder cases its word to match. */
    val isShifted: Boolean get() = shifted

    fun setShifted(value: Boolean) {
        if (capsLock) return            // caps lock overrides sentence-case auto-shift
        if (shifted != value) {
            shifted = value
            if (layer == Layer.LETTERS) rebuild()
        }
    }

    private fun tap() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    /** How far the finger must travel before a drag is considered a word trace. */
    private val traceStartDist = dpf(22)
    /** Minimum spacing between recorded trace points. */
    private val traceMinStep = dpf(5)

    private val DOUBLE_TAP_MS = 300L
    private val BACKSPACE_INITIAL_DELAY_MS = 400L   // pause before key-repeat kicks in
    private val BACKSPACE_CHAR_INTERVAL_MS = 95L    // per-character repeat rate
    private val BACKSPACE_WORD_AFTER_MS = 1500L     // after this long holding, delete whole words
    private val BACKSPACE_WORD_INTERVAL_MS = 190L   // per-word repeat rate
    // Longer than Android's 500ms default. Forgetting a word is destructive and the strip's slots are
    // narrow, so a hold has to be unmistakably a hold rather than a slow, badly aimed tap.
    private val SUGGESTION_HOLD_MS = 650L
    // How long the finger must sit still before a downward swipe is even considered for dismiss — see
    // the class doc and onTouchEvent. Comfortably past a real trace's start (single-digit milliseconds
    // at any ordinary swipe speed), short enough that a deliberate close doesn't feel delayed.
    private val DISMISS_HOLD_MS = 180L

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
    private fun dpf(v: Float): Float = v * resources.displayMetrics.density
    private fun spf(v: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics)

    private companion object {
        /** Cap on recorded trace points. A word trace across this keyboard is a few dozen; the cap is
         *  a guard against a finger held down for a very long time, not a normal limit. */
        const val MAX_TRACE_POINTS = 192
    }
}
