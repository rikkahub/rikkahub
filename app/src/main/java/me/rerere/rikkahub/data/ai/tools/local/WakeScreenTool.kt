package me.rerere.rikkahub.data.ai.tools.local

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Reusable helpers so launch_app and any other tool that needs the screen on can opt in
 * without duplicating the wake-lock dance.
 */
internal object ScreenWaker {
    fun isInteractive(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    fun isKeyguardLocked(ctx: Context): Boolean =
        ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    fun isKeyguardSecure(ctx: Context): Boolean =
        ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardSecure == true

    /**
     * Wake the screen if currently off. Uses an ACQUIRE_CAUSES_WAKEUP wake lock held briefly
     * (`holdMs`) — long enough for the OS to render a frame, then released so we are not
     * pinning the CPU. FULL_WAKE_LOCK is deprecated since API 17 but still functional for
     * the "turn the display on" use case; the supported alternative
     * (`KeyguardManager.requestDismissKeyguard`) only works from a foreground Activity.
     */
    @Suppress("DEPRECATION")
    fun wakeIfOff(ctx: Context, holdMs: Long = 3_000L): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        if (pm.isInteractive) return false
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "rikkahub:wake_screen"
        )
        return try {
            wl.acquire(holdMs)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

fun wakeScreenTool(context: Context): Tool = Tool(
    name = "wake_screen",
    description = """
        Turn the device's screen on if it is off. Uses a brief wake lock; CPU is not pinned
        afterwards. Returns {success, was_off, woke, keyguard_locked, keyguard_secure}. If the
        keyguard is locked AND secure (PIN/pattern/biometric), the user must unlock manually
        - this tool does not bypass real locks. Use this BEFORE launch_app or any screen-
        automation gesture when the screen has been off; otherwise the activity launches
        invisibly behind the lock screen and your read_window_tree calls will see nothing.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("hold_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "How long to hold the wake lock in ms (default 3000, max 30000)")
                })
            }
        )
    },
    execute = { input ->
        val holdMs = input.jsonObject["hold_ms"]?.jsonPrimitive?.intOrNull
            ?.coerceIn(500, 30_000)?.toLong() ?: 3_000L
        val wasOff = !ScreenWaker.isInteractive(context)
        val woke = if (wasOff) ScreenWaker.wakeIfOff(context, holdMs) else false
        val keyLocked = ScreenWaker.isKeyguardLocked(context)
        val keySecure = ScreenWaker.isKeyguardSecure(context)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("was_off", wasOff)
                    put("woke", woke)
                    put("keyguard_locked", keyLocked)
                    put("keyguard_secure", keySecure)
                    if (keyLocked && keySecure) {
                        put("warn", "Screen woke but keyguard is PIN-protected. The user must unlock the device for any screen automation to be useful.")
                    }
                }.toString()
            )
        )
    }
)
