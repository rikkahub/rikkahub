package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.ui.components.ui.CardGroup

/**
 * Sideload (non-Play) flavor: the YOLO ("bypass all restriction") danger zone — a toggle, a one-time
 * danger-consent dialog, and a persistent warning banner while it is on. YOLO grants the agent
 * unrestricted automation authority (every app incl. system UI and the host, every verb, every sink
 * incl. submit/send/pay), so it is PHYSICALLY ABSENT from the Play build (the unrestricted surface is
 * sideload-only, mirroring the workspace shell security boundary). The play copy of this seam is empty.
 *
 * Consent state machine: OFF --enable--> (acknowledged? ON : consent dialog) --accept--> ON (+ persist
 * the global acknowledgement). Disabling never clears the acknowledgement, so a later re-enable skips
 * the dialog; the warning banner keeps the danger visible whenever it is on. The lease derivation only
 * honors the flag once acknowledged AND from the standing assistant grant (a per-run grant can never
 * widen to YOLO), and the on-screen STOP kill switch is unaffected.
 */
@Composable
internal fun AutomationYoloSection(
    grant: AutomationGrant,
    yoloAcknowledged: Boolean,
    onUpdate: (AutomationGrant) -> Unit,
    onAcknowledge: () -> Unit,
) {
    var showConsent by remember { mutableStateOf(false) }

    // Enabling YOLO also flips the master switch on and seeds the conservative lease defaults so the
    // grant is immediately effective (a YOLO grant whose master switch is off would derive nothing).
    fun enableYolo() = onUpdate(grant.withEnabled(true).copy(yolo = true))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "YOLO mode (bypass all restriction)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        CardGroup {
            item(
                headlineContent = { Text("Enable YOLO mode") },
                supportingContent = {
                    Text(
                        "Lets automation observe and act on ANY app — including system UI and this " +
                            "app itself — and auto-confirms submit/send/pay taps. The package scope, " +
                            "verb, and sink limits above are ignored while it is on.",
                    )
                },
                trailingContent = {
                    Switch(
                        checked = grant.yolo,
                        onCheckedChange = { enable ->
                            when {
                                !enable -> onUpdate(grant.copy(yolo = false))
                                yoloAcknowledged -> enableYolo()
                                else -> showConsent = true
                            }
                        },
                    )
                },
            )
        }
        if (grant.yolo) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(imageVector = HugeIcons.AlertCircle, contentDescription = null)
                    Text(
                        text = "YOLO is ON. The agent can drive any app on this device with no scope " +
                            "limit and can press submit/send/pay without asking. Use the on-screen STOP " +
                            "overlay to abort at any time.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            icon = { Icon(imageVector = HugeIcons.AlertCircle, contentDescription = null) },
            title = { Text("Enable dangerous YOLO mode?") },
            text = {
                Text(
                    "YOLO mode removes every automation safety scope:\n\n" +
                        "• The agent can observe and act on ANY app — including system permission " +
                        "dialogs and this app itself.\n" +
                        "• Submit / send / pay taps are auto-confirmed — there is no per-action prompt.\n" +
                        "• The package, verb, and sink limits you configured are ignored.\n\n" +
                        "The on-screen STOP kill switch still works and the time/step lease still " +
                        "applies. Enable only if you understand and accept the risk.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAcknowledge()
                        enableYolo()
                        showConsent = false
                    },
                ) { Text("I understand, enable") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text("Cancel") }
            },
        )
    }
}
