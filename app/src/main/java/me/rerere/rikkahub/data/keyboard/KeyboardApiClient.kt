package me.rerere.rikkahub.data.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dev.patrickgold.florisboard.api.EditorInfoBundle
import dev.patrickgold.florisboard.api.IKeyboardApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Binds RikkaHub to the co-signed agent-keyboard ([KEYBOARD_PACKAGE]) AIDL service and
 * drives the active text field through it.
 *
 * Lifecycle the client manages for the caller:
 *  - **Bind**: lazily binds [BIND_ACTION] on first use, blocking up to [BIND_TIMEOUT_MS].
 *  - **Handshake**: calls [IKeyboardApi.connect] once per bind, caches the returned session
 *    token, and reuses it on every later call.
 *  - **Recovery**: if the binding drops (OEM kill, keyboard process death) the cached binder
 *    and token are cleared; the next call re-binds + re-handshakes. If a call returns the
 *    "failed" sentinel (false / null) it is retried exactly once against a fresh handshake,
 *    because a stale token after an unbind/rebind race looks identical to an operational
 *    failure at the AIDL boundary.
 *
 * Every public method is time-bounded ([CALL_TIMEOUT_MS]) so a hung binder transaction can
 * never stall a tool call (and, through it, the Telegram bot's generation wait).
 */
class KeyboardApiClient(private val context: Context) {

    /** Why a call could not be completed — surfaced to the tool layer as a recovery hint. */
    enum class Failure {
        /** agent-keyboard is not installed on the device. */
        NOT_INSTALLED,

        /** bindService returned false or never connected — usually missing co-signing. */
        BIND_REFUSED,

        /** Bound, handshake done, but the operation itself failed (no focused field,
         *  password field, rate-limited, or the IME is not the active keyboard). */
        OPERATION_FAILED,
    }

    /** A successful value [T], or a [Failure] describing why the call could not complete. */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val failure: Failure) : Result<Nothing>
    }

    private val lock = Mutex()
    private var binder: IKeyboardApi? = null
    private var token: String? = null
    private var connection: ServiceConnection? = null

    @Volatile
    private var bindWaiter: CompletableDeferred<IKeyboardApi?>? = null

    /** True if agent-keyboard is installed (does not prove it is the active IME). */
    fun isKeyboardInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(KEYBOARD_PACKAGE, 0)
        true
    }.getOrDefault(false)

    // -- public AIDL wrappers -----------------------------------------------------------

    suspend fun typeText(text: String): Result<Unit> =
        boolCall { api, t -> api.typeText(t, text) }

    suspend fun pressKey(keyCode: Int): Result<Unit> =
        boolCall { api, t -> api.pressKey(t, keyCode) }

    suspend fun deleteChars(count: Int): Result<Unit> =
        boolCall { api, t -> api.deleteChars(t, count) }

    suspend fun clearField(): Result<Unit> =
        boolCall { api, t -> api.clearField(t) }

    suspend fun setCursorPosition(pos: Int): Result<Unit> =
        boolCall { api, t -> api.setCursorPosition(t, pos) }

    suspend fun selectRange(start: Int, end: Int): Result<Unit> =
        boolCall { api, t -> api.selectRange(t, start, end) }

    suspend fun getCurrentText(): Result<String?> =
        stringCall { api, t -> api.getCurrentText(t) }

    suspend fun getSelectedText(): Result<String?> =
        stringCall { api, t -> api.getSelectedText(t) }

    suspend fun getEditorInfo(): Result<EditorInfoBundle?> =
        nullableCall { api, t -> api.getEditorInfo(t) }

    // -- call machinery -----------------------------------------------------------------

    /**
     * Boolean-returning AIDL calls: `false` is the failure sentinel. Retries once against a
     * fresh handshake before reporting [Failure.OPERATION_FAILED].
     */
    private suspend fun boolCall(block: (IKeyboardApi, String) -> Boolean): Result<Unit> {
        val first = invoke { api, t -> if (block(api, t)) true else null }
        if (first is Result.Ok) return Result.Ok(Unit)
        if (first is Result.Err && first.failure != Failure.OPERATION_FAILED) return first
        // Stale token looks like an operational fail at the boundary — retry once fresh.
        val retry = invoke(forceHandshake = true) { api, t -> if (block(api, t)) true else null }
        return if (retry is Result.Ok) Result.Ok(Unit) else Result.Err(Failure.OPERATION_FAILED)
    }

    /**
     * String-returning AIDL calls: `null` is the failure sentinel. We cannot distinguish a
     * legitimately-empty field from a rejection, so an empty/`null` read is retried once
     * fresh; if it is still `null` we report it as a successful (empty) read rather than an
     * error, because read tools must not lie about a genuinely empty field.
     */
    private suspend fun stringCall(block: (IKeyboardApi, String) -> String?): Result<String?> {
        val first = invoke { api, t -> block(api, t) ?: SENTINEL_NULL }
        if (first is Result.Ok) return Result.Ok(first.value.takeIf { it != SENTINEL_NULL })
        if (first is Result.Err && first.failure != Failure.OPERATION_FAILED) return first
        val retry = invoke(forceHandshake = true) { api, t -> block(api, t) ?: SENTINEL_NULL }
        return if (retry is Result.Ok) {
            Result.Ok(retry.value.takeIf { it != SENTINEL_NULL })
        } else {
            // Genuinely no readable text (no focused field / password). Report as Err so the
            // tool can render the install-or-set-IME recovery hint.
            Result.Err(Failure.OPERATION_FAILED)
        }
    }

    private suspend fun nullableCall(
        block: (IKeyboardApi, String) -> EditorInfoBundle?,
    ): Result<EditorInfoBundle?> {
        val first = invoke { api, t -> block(api, t) }
        if (first is Result.Ok) return Result.Ok(first.value)
        if (first is Result.Err && first.failure != Failure.OPERATION_FAILED) return first
        val retry = invoke(forceHandshake = true) { api, t -> block(api, t) }
        return if (retry is Result.Ok) retry else Result.Err(Failure.OPERATION_FAILED)
    }

    /**
     * Ensures a live binding + valid token, then runs [block]. A `null` return from [block]
     * is the AIDL failure sentinel and maps to [Failure.OPERATION_FAILED].
     */
    private suspend fun <T : Any> invoke(
        forceHandshake: Boolean = false,
        block: (IKeyboardApi, String) -> T?,
    ): Result<T> {
        if (!isKeyboardInstalled()) return Result.Err(Failure.NOT_INSTALLED)
        val api = ensureBound() ?: return Result.Err(Failure.BIND_REFUSED)
        val sessionToken = ensureToken(api, forceHandshake)
            ?: return Result.Err(Failure.BIND_REFUSED)
        val value = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            runCatching { block(api, sessionToken) }.getOrElse { t ->
                Log.w(TAG, "AIDL call threw — dropping binding", t)
                resetBinding()
                null
            }
        }
        return if (value != null) Result.Ok(value) else Result.Err(Failure.OPERATION_FAILED)
    }

    private suspend fun ensureToken(api: IKeyboardApi, force: Boolean): String? = lock.withLock {
        if (!force) token?.let { return it }
        val fresh = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            runCatching { api.connect() }.getOrNull()
        }
        token = fresh
        fresh
    }

    private suspend fun ensureBound(): IKeyboardApi? {
        lock.withLock { binder?.let { return it } }
        val deferred: CompletableDeferred<IKeyboardApi?>
        val needBind: Boolean
        lock.withLock {
            binder?.let { return it }
            if (bindWaiter == null) {
                bindWaiter = CompletableDeferred()
                needBind = true
            } else {
                needBind = false
            }
            deferred = bindWaiter!!
        }
        if (needBind) startBind(deferred)
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun startBind(deferred: CompletableDeferred<IKeyboardApi?>) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val api = service?.let { IKeyboardApi.Stub.asInterface(it) }
                runCatchingBlocking {
                    lock.withLock {
                        binder = api
                        token = null
                        bindWaiter = null
                    }
                }
                deferred.complete(api)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "agent-keyboard service disconnected")
                runCatchingBlocking { resetBinding() }
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.w(TAG, "agent-keyboard binding died")
                runCatchingBlocking { resetBinding() }
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }
        val intent = Intent(BIND_ACTION).setPackage(KEYBOARD_PACKAGE)
        val ok = runCatching {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (ok) {
            lock.withLock { connection = conn }
        } else {
            Log.w(TAG, "bindService refused for agent-keyboard (co-signing / permission?)")
            runCatching { context.unbindService(conn) }
            lock.withLock { bindWaiter = null }
            deferred.complete(null)
        }
    }

    private suspend fun resetBinding() = lock.withLock {
        binder = null
        token = null
        bindWaiter = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun runCatchingBlocking(block: suspend () -> Unit) {
        runCatching { kotlinx.coroutines.runBlocking { block() } }
    }

    companion object {
        private const val TAG = "KeyboardApiClient"
        const val KEYBOARD_PACKAGE = "dev.patrickgold.florisboard"
        const val BIND_ACTION = "dev.patrickgold.florisboard.api.action.BIND"
        private const val BIND_TIMEOUT_MS = 5_000L
        private const val CALL_TIMEOUT_MS = 5_000L
        private const val SENTINEL_NULL = " __keyboard_api_null__"
    }
}
