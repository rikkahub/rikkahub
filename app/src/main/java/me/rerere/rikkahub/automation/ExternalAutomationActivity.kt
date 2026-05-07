package me.rerere.rikkahub.automation

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Exported activity that accepts external automation intents.
 *
 *  * `me.rerere.rikkahub.RUN_TASK` — fire-and-forget headless run. Activity finishes
 *    immediately after handing off to the dispatcher; the dispatcher runs the generation
 *    on the app scope and posts callback broadcasts to the caller's `return_action` /
 *    `return_package` if those extras were provided.
 *  * `me.rerere.rikkahub.RUN_CHAT` — interactive open-the-chat. Reserved; returns
 *    `rejected:not_implemented` in v1 because the UI route to pre-fill the chat input bar
 *    isn't wired yet. Spec marks this as a v1-deferred path.
 *
 * Trust gate is enforced here in the Activity surface: callers not on the trusted list are
 * rejected for v1. The "show one-time approval dialog" behaviour from the spec is
 * deferred — adding it requires a Material 3 dialog, which means inflating a Compose root
 * inside an invisible Activity, which means a noticeable startup-flash for trusted callers.
 * v1 keeps this simple and conservative: untrusted callers are denied; the user must add
 * the package to the trusted list from Settings before it can fire.
 *
 * The Activity uses `Theme.Translucent.NoTitleBar` so it never paints a window before
 * `finish()` — important for the autonomous-fire UX (Tasker hands off → no flash).
 */
class ExternalAutomationActivity : Activity() {

    private val config: ExternalAutomationConfig by inject()
    private val dispatcher: ExternalAutomationDispatcher by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent ?: run { finish(); return }
        val callerPkg = callingPackage ?: referrer?.host
        val action = intent.action
        val requestId = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_REQUEST_ID)
        val returnAction = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_RETURN_ACTION)
        val returnPackage = intent.getStringExtra(ExternalAutomationDispatcher.EXTRA_RETURN_PACKAGE)

        // Run the trust + dispatch on a coroutine — the Activity itself can finish() right
        // after queuing the work. The dispatcher uses appScope so the run survives
        // Activity teardown.
        CoroutineScope(Dispatchers.Default).launch {
            try {
                handleAction(action, intent, callerPkg, requestId, returnAction, returnPackage)
            } catch (t: Throwable) {
                Log.w(TAG, "external automation activity dispatch failed", t)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }

    private suspend fun handleAction(
        action: String?,
        intent: android.content.Intent,
        callerPkg: String?,
        requestId: String?,
        returnAction: String?,
        returnPackage: String?,
    ) {
        when (val classification = dispatcher.classifyCaller(callerPkg)) {
            is ExternalAutomationDispatcher.TrustResult.Disabled -> {
                dispatcher.rejectAndCallback(callerPkg.orEmpty(), action.orEmpty(), requestId, returnAction, returnPackage, "feature_disabled")
                return
            }
            is ExternalAutomationDispatcher.TrustResult.PendingUserApproval -> {
                // Conservative v1: deny + tell the user via the invocation log. Dialog
                // flow is deferred per the class kdoc.
                dispatcher.rejectAndCallback(callerPkg.orEmpty(), action.orEmpty(), requestId, returnAction, returnPackage, "untrusted_caller")
                return
            }
            is ExternalAutomationDispatcher.TrustResult.Trusted -> { /* proceed */ }
        }

        when (action) {
            ExternalAutomationDispatcher.ACTION_RUN_TASK -> {
                val prompt = ExternalAutomationDispatcher.extractPrompt(
                    intent,
                    ExternalAutomationDispatcher.EXTRA_TASK,
                    ExternalAutomationDispatcher.EXTRA_TASK_B64,
                )
                if (prompt.isNullOrBlank()) {
                    dispatcher.rejectAndCallback(callerPkg.orEmpty(), action, requestId, returnAction, returnPackage, "missing_prompt")
                    return
                }
                dispatcher.dispatchTask(prompt, callerPkg.orEmpty(), requestId, returnAction, returnPackage)
            }
            ExternalAutomationDispatcher.ACTION_RUN_CHAT -> {
                // v1: not implemented. Spec mandates the action surface but the UI plumbing
                // (open MainActivity with prefilled chat) is deferred.
                dispatcher.rejectAndCallback(callerPkg.orEmpty(), action, requestId, returnAction, returnPackage, "not_implemented_in_v1")
            }
            else -> {
                dispatcher.rejectAndCallback(callerPkg.orEmpty(), action.orEmpty(), requestId, returnAction, returnPackage, "unknown_action")
            }
        }
    }

    companion object {
        private const val TAG = "ExtAutomation"
    }
}
