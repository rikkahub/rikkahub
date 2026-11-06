package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import me.rerere.rikkahub.data.datastore.DisplaySetting

// 顶部栏按钮的浮动胶囊容器，背景效果与底部输入框保持一致；
// 背景单独一层，按 [showBackground] 淡入淡出，按钮本身始终显示
@Composable
fun ChatTopBarCapsule(
    hazeState: HazeState,
    displaySetting: DisplaySetting,
    showBackground: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (showBackground) 1f else 0f,
        label = "ChatTopBarCapsuleBackground",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (backgroundAlpha > 0f) {
            Surface(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = backgroundAlpha }
                    .clip(CircleShape)
                    .chatInputHaze(
                        hazeState = hazeState,
                        displaySetting = displaySetting,
                        containerColor = containerColor,
                        shape = CircleShape,
                    ),
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = if (displaySetting.enableBlurEffect) Color.Transparent else containerColor,
                content = {},
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
