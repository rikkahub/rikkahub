package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

// sheet 的头部：左侧标题与说明，右侧放操作
@Composable
internal fun PickerHeader(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    hero: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                // 各语言标题长短差别很大，放不下两行时自动缩小字号
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 28.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineMediumEmphasized.copy(
                    fontWeight = FontWeight.Black,
                    lineHeight = 1.2.em,
                    lineBreak = LineBreak.Heading,
                ),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hero()
    }
}

// 选择器 sheet 的紧凑头部：左侧依次是小标题、当前选项和说明，右侧放随选项变形的形状
@Composable
internal fun <T : Comparable<T>> PickerValueHeader(
    title: String,
    value: T,
    hint: String,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String,
    hero: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PickerValueLabel(value = value, label = label)
            Text(
                text = hint,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hero()
    }
}

// 形状随 index 切换，相邻形状之间用 Morph 连续过渡
@Composable
internal fun PickerHero(
    shapes: List<RoundedPolygon>,
    index: Int,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val morphs = remember(shapes) { shapes.zipWithNext { from, to -> Morph(from, to) } }
    val path = remember { Path() }
    val position by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
    )
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val iconSpatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val iconEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    Box(
        modifier = modifier
            .size(72.dp)
            .drawBehind {
                // 弹簧会过冲，position 可能略微越界
                val segment = position.toInt().coerceIn(0, morphs.lastIndex)
                morphs[segment].toPath(
                    progress = (position - segment).coerceIn(0f, 1f),
                    path = path,
                )
                withTransform({
                    rotate(position * 30f)
                    // MaterialShapes 是归一化到 1x1 的
                    scale(size.width, size.height, pivot = Offset.Zero)
                }) {
                    drawPath(path, animatedContainerColor)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (fadeIn(iconEffectsSpec) + scaleIn(iconSpatialSpec, initialScale = 0.6f)) togetherWith
                    (fadeOut(iconEffectsSpec) + scaleOut(iconSpatialSpec, targetScale = 0.6f))
            },
        ) { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = animatedContentColor,
            )
        }
    }
}

// 当前选项的名称，切换时上下滚动
@Composable
private fun <T : Comparable<T>> PickerValueLabel(
    value: T,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String,
) {
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            // 值变大时向上滚动，变小时向下滚动
            val direction = if (targetState > initialState) 1 else -1
            (slideInVertically(spatialSpec) { it * direction / 2 } + fadeIn(effectsSpec)) togetherWith
                (slideOutVertically(spatialSpec) { -it * direction / 2 } + fadeOut(effectsSpec)) using
                SizeTransform(clip = false)
        },
    ) {
        Text(
            text = label(it),
            // 个别语言的选项名很长，放不下两行时自动缩小字号
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 24.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.headlineSmallEmphasized.copy(
                fontWeight = FontWeight.Black,
                lineHeight = 1.2.em,
                lineBreak = LineBreak.Heading,
            ),
        )
    }
}

// sheet 内子页面的头部：返回按钮加标题
@Composable
internal fun SheetHeader(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigationIcon?.invoke()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (navigationIcon == null) 8.dp else 4.dp),
        )
        actions()
    }
}
