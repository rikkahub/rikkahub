package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SshHostEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshHostFormSheet(
    existing: SshHostEntity? = null,
    onDismiss: () -> Unit,
    onSave: (SshHostEntity) -> Unit,
) {
    val isEdit = existing != null
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var portText by remember { mutableStateOf(existing?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var privateKey by remember { mutableStateOf(existing?.privateKey ?: "") }
    var passphrase by remember { mutableStateOf(existing?.passphrase ?: "") }
    var authMode by remember { mutableStateOf(
        if (existing?.privateKey != null) AuthMode.PRIVATE_KEY else AuthMode.PASSWORD
    ) }
    var nameError by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf(false) }
    var userError by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(if (isEdit) R.string.setting_ssh_edit else R.string.setting_ssh_add),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it; nameError = false },
                label = { Text(stringResource(R.string.setting_ssh_field_name)) },
                isError = nameError,
                supportingText = if (nameError) {{
                    Text(stringResource(R.string.setting_ssh_validation_required, stringResource(R.string.setting_ssh_field_name)))
                }} else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = host,
                onValueChange = { host = it; hostError = false },
                label = { Text(stringResource(R.string.setting_ssh_field_host)) },
                isError = hostError,
                supportingText = if (hostError) {{
                    Text(stringResource(R.string.setting_ssh_validation_required, stringResource(R.string.setting_ssh_field_host)))
                }} else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.setting_ssh_field_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = user,
                onValueChange = { user = it; userError = false },
                label = { Text(stringResource(R.string.setting_ssh_field_user)) },
                isError = userError,
                supportingText = if (userError) {{
                    Text(stringResource(R.string.setting_ssh_validation_required, stringResource(R.string.setting_ssh_field_user)))
                }} else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = authMode == AuthMode.PASSWORD,
                    onClick = { authMode = AuthMode.PASSWORD },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.setting_ssh_auth_password))
                }
                SegmentedButton(
                    selected = authMode == AuthMode.PRIVATE_KEY,
                    onClick = { authMode = AuthMode.PRIVATE_KEY },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.setting_ssh_auth_private_key))
                }
            }
            Spacer(Modifier.height(8.dp))

            if (authMode == AuthMode.PASSWORD) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.setting_ssh_field_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = { Text(stringResource(R.string.setting_ssh_field_private_key)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.setting_ssh_field_passphrase)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        nameError = name.isBlank()
                        hostError = host.isBlank()
                        userError = user.isBlank()
                        if (nameError || hostError || userError) return@TextButton
                        val port = portText.toIntOrNull() ?: 22
                        onSave(
                            SshHostEntity(
                                name = name.trim(),
                                host = host.trim(),
                                port = port,
                                user = user.trim(),
                                password = if (authMode == AuthMode.PASSWORD) password.ifBlank { null } else existing?.password,
                                privateKey = if (authMode == AuthMode.PRIVATE_KEY) privateKey.ifBlank { null } else existing?.privateKey,
                                passphrase = if (authMode == AuthMode.PRIVATE_KEY) passphrase.ifBlank { null } else null,
                                createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.setting_ssh_save))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private enum class AuthMode { PASSWORD, PRIVATE_KEY }
