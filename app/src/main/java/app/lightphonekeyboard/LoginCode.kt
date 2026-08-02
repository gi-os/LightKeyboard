package app.lightphonekeyboard

import android.content.Context
import android.net.Uri

/**
 * The one-time login code LightChat is currently holding, if there is one.
 *
 * **Why the keyboard is where this belongs.** A verification code arrives in one app and is
 * wanted in another, and the walk between them is done from memory, six digits at a time, in
 * the one moment the user cannot afford to mistype. iOS puts the code above the keys. Android
 * does it through autofill and `SmsRetriever`, both of which need Play Services and a real SMS
 * app — and on this phone the codes arrive over BlueBubbles rather than the carrier, so neither
 * would see them anyway. The keyboard is the only surface present in both apps.
 *
 * **Read, never stored.** Nothing here is cached across fields or written to Prefs. The value
 * lives in LightChat for three minutes and expires there; keeping a copy would mean this app
 * holding a code after the app that received it had already decided it was too old, which is
 * the one way a suggestion strip can be confidently wrong.
 *
 * **Absent LightChat this is silently nothing.** An unresolvable authority throws from the
 * resolver rather than returning null, which is why the whole call is wrapped: a keyboard that
 * crashed on a phone without a messaging app installed would be a spectacular way to lose a
 * feature nobody asked for.
 */
object LoginCode {

    /**
     * LightChat's authority, hardcoded — there is no shared constant to import across two
     * packages, and it is fixed rather than derived on the far side for exactly this reason.
     */
    private val CONTENT_URI: Uri = Uri.parse("content://com.gios.lightchat.code/code")

    private const val COLUMN_CODE = "code"

    /**
     * A code short enough to be worth offering, and long enough to be one.
     *
     * Re-checked here rather than trusted, even though LightChat checks it too. This app has to
     * decide what to put in front of the user; "the other side promised" is not something the
     * strip can verify at the moment it renders, and the check costs two comparisons.
     */
    private const val MIN = 4
    private const val MAX = 8

    fun current(context: Context): String? = runCatching {
        context.contentResolver.query(CONTENT_URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val at = c.getColumnIndex(COLUMN_CODE)
            if (at < 0) return@use null
            c.getString(at)?.trim()?.takeIf { plausible(it) }
        }
    }.getOrNull()

    private fun plausible(code: String): Boolean =
        code.length in MIN..MAX && code.all { it.isLetterOrDigit() } && code.any { it.isDigit() }
}
