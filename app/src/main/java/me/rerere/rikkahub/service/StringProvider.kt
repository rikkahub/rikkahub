package me.rerere.rikkahub.service

import android.content.Context

/**
 * Resolves string resources for ChatService (#360 P1b). Extracted so ChatService no longer holds an
 * Android `Application`/`Context` just to read error-title / status strings — the LAST direct Android
 * dependency in its constructor after the P1a platform seams. A test supplies a fake that returns the
 * resId (or a stub) without a Context; production wraps the app Context.
 *
 * The port takes the Android resource id (Int) — that is the app's own string-key namespace (R.string.*),
 * not an Android framework TYPE crossing the boundary — so callers stay terse (`strings.getString(R...)`).
 */
interface StringProvider {
    fun getString(resId: Int): String
    fun getString(resId: Int, vararg formatArgs: Any): String
}

/** Production [StringProvider] backed by the app [Context]. */
class AndroidStringProvider(private val context: Context) : StringProvider {
    override fun getString(resId: Int): String = context.getString(resId)
    override fun getString(resId: Int, vararg formatArgs: Any): String =
        context.getString(resId, *formatArgs)
}
