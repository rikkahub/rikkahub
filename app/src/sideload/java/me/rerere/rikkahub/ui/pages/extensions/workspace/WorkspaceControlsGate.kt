package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.isShellRunnable
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

// Sideload (non-Play) flavor: the shell-enable toggle + rootfs-install action + terminal entry
// (issue #197 slice 6b). These controls drive the LLM-shell / PRoot sandbox, so they are PHYSICALLY
// ABSENT from the Play APK (I-FLAVOR, design note security-model-design:197 §4.1 Option A) — the play
// copy of this seam stays empty. The shell-enablement invariant is enforced one layer down at
// WorkspaceRepository.executeCommand (I-ENABLE); this UI only flips the persisted toggle/install
// state. The VM (install/shell methods on the shared main WorkspaceDetailVM) is never invoked on Play
// because the play seam never renders this body.
@Composable
internal fun SideloadWorkspaceControls(workspace: WorkspaceEntity?) {
    if (workspace == null) {
        // No row yet (initial Flow emission). Render the controls disabled rather than nothing so the
        // card does not pop in late; the VM is keyed by the workspace id from the parent page route.
        return
    }
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(workspace.id) })
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    var showInstallDialog by remember { mutableStateOf(false) }

    val buttonState = rootfsInstallButtonState(
        shellStatus = workspace.shellStatus,
        installProgressActive = installProgress != null,
    )
    val installButtonText = when {
        buttonState.installing -> stringResource(R.string.workspace_rootfs_installing)
        buttonState.rootfsReady -> stringResource(R.string.workspace_rootfs_reinstall)
        else -> stringResource(R.string.workspace_rootfs_install)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_shell_enable_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.workspace_shell_enable_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = workspace.shellEnabled,
                    onCheckedChange = vm::setShellEnabled,
                    enabled = installProgress == null,
                )
            }

            Button(
                onClick = { showInstallDialog = true },
                enabled = !buttonState.installing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Bash, contentDescription = null)
                Text(
                    text = installButtonText,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            OutlinedButton(
                // I-ENABLE: enable the terminal entry only when the shell is actually runnable
                // (enabled AND rootfs READY) — the same gate the executeCommand sink enforces — not on
                // shellEnabled alone, so a BROKEN/INSTALLING workspace cannot open an interactive shell.
                onClick = { navController.navigate(Screen.WorkspaceTerminal(workspace.id)) },
                enabled = isShellRunnable(workspace.shellEnabled, workspace.shellStatus),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                Text(
                    text = stringResource(R.string.workspace_terminal_open),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            installProgress?.let { progress ->
                RootfsProgress(progress)
            }
        }
    }

    if (showInstallDialog) {
        InstallRootfsDialog(
            workspace = workspace,
            onDismiss = { showInstallDialog = false },
            onConfirm = { url ->
                showInstallDialog = false
                vm.installRootfs(url)
            },
        )
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.workspace_rootfs_install_dialog_confirm))
                }
            },
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${formatBytes(it)}" }.orEmpty()
                    stringResource(R.string.workspace_rootfs_progress_downloading, "${formatBytes(progress.bytesRead)}$total")
                }

                RootfsInstallStage.EXTRACTING ->
                    stringResource(R.string.workspace_rootfs_progress_extracting, progress.entriesExtracted)

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_rootfs_progress_installed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_rootfs_install_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_rootfs_install_dialog_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_rootfs_install_dialog_url_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.workspace_rootfs_install_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.workspace_rootfs_install_dialog_cancel))
            }
        },
    )
}

/**
 * Whether the install button shows the "installing" / "ready" state. Extracted as a pure function so
 * the install-button state machine is unit-testable on the JVM without Compose (see
 * RootfsInstallButtonStateTest). [installing] is true when EITHER a live install is in flight
 * ([installProgressActive]) OR the persisted row is still INSTALLING — so the button reflects an
 * install that started before the progress object arrives and one whose progress already cleared but
 * whose row has not yet flipped to READY/BROKEN. [rootfsReady] is true only when the row says READY,
 * driving the install -> reinstall label swap. Mirrors upstream:
 * `installing = installProgress != null || status == INSTALLING; rootfsReady = status == READY`.
 */
internal fun rootfsInstallButtonState(
    shellStatus: String?,
    installProgressActive: Boolean,
): RootfsInstallButtonState = RootfsInstallButtonState(
    installing = installProgressActive || shellStatus == WorkspaceShellStatus.INSTALLING.name,
    rootfsReady = shellStatus == WorkspaceShellStatus.READY.name,
)

internal data class RootfsInstallButtonState(
    val installing: Boolean,
    val rootfsReady: Boolean,
)

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

// Ubuntu base 24.04 arm64 rootfs (carried verbatim from upstream 22b280f3). RootfsInstaller enforces
// HTTPS-only on this URL (and on any redirect target) before downloading, so the rootfs an LLM shell
// runs inside cannot be MITM-swapped.
private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
