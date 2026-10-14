package me.rerere.ui.toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.InformationCircle
import kotlin.math.abs
import kotlin.math.sign

/**
 * 直接画在调用处的布局里，不开新窗口，toast 以外的区域不拦截触摸。
 * 需要放在页面内容之上（例如根 Box 的最后一个子节点）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Toaster(
    state: ToasterState,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 新的 toast 在最上面
        state.items.asReversed().forEach { item ->
            key(item.id) {
                LaunchedEffect(item) {
                    snapshotFlow { item.visibleState.isIdle && !item.visibleState.currentState }.first { it }
                    state.remove(item)
                }
                AnimatedVisibility(
                    visibleState = item.visibleState,
                    enter = slideInVertically(motionScheme.defaultSpatialSpec()) { -it } +
                        expandVertically(motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                        fadeIn(motionScheme.defaultEffectsSpec()),
                    exit = shrinkVertically(motionScheme.fastSpatialSpec(), shrinkTowards = Alignment.Top) +
                        scaleOut(motionScheme.fastEffectsSpec(), targetScale = 0.9f) +
                        fadeOut(motionScheme.fastEffectsSpec()),
                ) {
                    ToastContent(
                        item = item,
                        onDismiss = { state.dismiss(item) },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToastContent(
    item: ToastItem,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = toastColors(item.type)
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val draggableState = rememberDraggableState { delta ->
        scope.launch { offsetX.snapTo(offsetX.value + delta) }
    }

    Surface(
        onClick = onDismiss,
        shape = RoundedCornerShape(24.dp),
        color = colors.container,
        contentColor = colors.content,
        shadowElevation = 6.dp,
        modifier = modifier
            .onSizeChanged { width = it.width }
            .graphicsLayer {
                translationX = offsetX.value
                alpha = 1f - (abs(offsetX.value) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
            // 左右滑动关闭
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    if (abs(offsetX.value) > width / 3f || abs(velocity) > 1500f) {
                        val direction = if (offsetX.value != 0f) sign(offsetX.value) else sign(velocity)
                        offsetX.animateTo(direction * width)
                        onDismiss()
                    } else {
                        offsetX.animateTo(0f)
                    }
                },
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(
                    start = if (colors.icon != null) 14.dp else 20.dp,
                    end = 20.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (colors.icon != null) {
                Icon(
                    imageVector = colors.icon,
                    contentDescription = null,
                    tint = colors.iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = item.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class ToastColors(
    val container: Color,
    val content: Color,
    val icon: ImageVector?,
    val iconTint: Color,
)

@Composable
private fun toastColors(type: ToastType): ToastColors {
    val scheme = MaterialTheme.colorScheme
    return when (type) {
        ToastType.Normal -> ToastColors(scheme.inverseSurface, scheme.inverseOnSurface, null, Color.Unspecified)
        ToastType.Success -> ToastColors(
            scheme.primaryContainer, scheme.onPrimaryContainer, HugeIcons.CheckmarkCircle02, scheme.primary
        )

        ToastType.Info -> ToastColors(
            scheme.secondaryContainer, scheme.onSecondaryContainer, HugeIcons.InformationCircle, scheme.secondary
        )

        ToastType.Warning -> ToastColors(
            scheme.tertiaryContainer, scheme.onTertiaryContainer, HugeIcons.Alert02, scheme.tertiary
        )

        ToastType.Error -> ToastColors(
            scheme.errorContainer, scheme.onErrorContainer, HugeIcons.AlertCircle, scheme.error
        )
    }
}
