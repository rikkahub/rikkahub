package me.rerere.rikkahub.ui.hooks

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope

@Composable
fun Modifier.heroAnimation(
    key: Any,
): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    return with(sharedTransitionScope) {
        this@heroAnimation.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope
        )
    }
}

/**
 * 容器变换：同一个 key 的两个容器（比如列表项和它打开的整页）在页面切换时互相展开、收回，内容交叉淡入淡出。
 * 两边都按宽度缩放、顶部对齐，不会在动画的每一帧重新测量整页。
 */
@Composable
fun Modifier.heroContainer(
    key: Any,
    shape: Shape,
): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    return with(sharedTransitionScope) {
        this@heroContainer.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}
