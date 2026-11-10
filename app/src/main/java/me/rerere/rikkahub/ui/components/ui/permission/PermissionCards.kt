package me.rerere.rikkahub.ui.components.ui.permission

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.rikkahub.R

/**
 * 本地网络权限状态, Android 17 以下不需要这个权限, 返回 null
 */
@Composable
fun rememberLocalNetworkPermissionState(): PermissionState? {
    return if (Build.VERSION.SDK_INT >= 37) rememberPermissionState(PermissionLocalNetwork) else null
}

/**
 * 通知权限状态, Android 13 以下不需要运行时申请, 返回 null
 */
@Composable
fun rememberNotificationPermissionState(): PermissionState? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(PermissionNotification)
    } else {
        null
    }
}

/**
 * 提示用户授予本地网络权限的卡片, 点击后发起申请
 *
 * 是否显示由调用方根据 [permissionState] 决定, 在 LazyColumn 里按条件添加 item, 避免留下空白间距
 */
@Composable
fun LocalNetworkPermissionCard(
    permissionState: PermissionState,
    modifier: Modifier = Modifier,
) {
    PermissionRequestCard(
        permissionState = permissionState,
        icon = HugeIcons.Alert01,
        title = stringResource(R.string.permission_local_network),
        description = stringResource(R.string.permission_local_network_desc),
        modifier = modifier,
    )
}

/**
 * 提示用户授予通知权限的卡片, 没有通知权限时前台服务（生成、Web 服务器等）的通知不会显示
 *
 * 是否显示由调用方根据 [permissionState] 决定, 在 LazyColumn 里按条件添加 item, 避免留下空白间距
 */
@Composable
fun NotificationPermissionCard(
    permissionState: PermissionState,
    modifier: Modifier = Modifier,
) {
    PermissionRequestCard(
        permissionState = permissionState,
        icon = HugeIcons.Notification01,
        title = stringResource(R.string.permission_notification),
        description = stringResource(R.string.permission_notification_card_desc),
        modifier = modifier,
    )
}

@Composable
private fun PermissionRequestCard(
    permissionState: PermissionState,
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    PermissionManager(permissionState = permissionState)

    Surface(
        onClick = { permissionState.requestPermissions() },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.tertiary, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
