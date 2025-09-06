package me.rerere.rikkahub.ui.components.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleWarning
import me.rerere.rikkahub.R

/**
 * 权限请求说明对话框
 */
@Composable
internal fun PermissionRationaleDialog(
    permissions: List<PermissionInfo>,
    permanentlyDeniedPermissions: List<PermissionInfo>,
    onProceed: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onCancel,
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题图标
                Icon(
                    imageVector = Lucide.MessageCircleWarning,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 标题
                val hasPermanentlyDenied = permanentlyDeniedPermissions.isNotEmpty()
                Text(
                    text = stringResource(R.string.permission_diaog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 说明文字
                Text(
                    text = if (hasPermanentlyDenied) {
                        stringResource(R.string.permission_desc_goto_setting)
                    } else {
                        stringResource(R.string.permission_desc_require_permission)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 权限列表
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(permissions) { permissionInfo ->
                        PermissionItem(
                            permissionInfo = permissionInfo,
                            isPermanentlyDenied = permanentlyDeniedPermissions.contains(permissionInfo),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 按钮组
                if (hasPermanentlyDenied) {
                    // 有永久拒绝的权限，只显示前往设置和取消按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }

                        Button(
                            onClick = onProceed, // 这里会跳转到设置
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.permission_go_to_settings))
                        }
                    }
                } else {
                    // 没有永久拒绝的权限，显示正常的授权按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.cancel))
                        }

                        Button(
                            onClick = onProceed,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个权限项组件
 */
@Composable
private fun PermissionItem(
    permissionInfo: PermissionInfo,
    modifier: Modifier = Modifier,
    isPermanentlyDenied: Boolean = false,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 权限名称
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ProvideTextStyle(value = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium)) {
                        permissionInfo.displayName()
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (permissionInfo.required) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.permission_required),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (isPermanentlyDenied) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            ),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.permission_permanently_denied),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 权限使用说明
            ProvideTextStyle(value = MaterialTheme.typography.bodySmall) {
                permissionInfo.usage()
            }
        }
    }
}
