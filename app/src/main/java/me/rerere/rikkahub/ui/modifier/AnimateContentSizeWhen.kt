package me.rerere.rikkahub.ui.modifier

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

fun Modifier.animateContentSizeWhen(
    enabled: Boolean,
    animationSpec: FiniteAnimationSpec<IntSize>? = null,
): Modifier = if (!enabled) {
    this
} else if (animationSpec != null) {
    this.animateContentSize(animationSpec)
} else {
    this.animateContentSize()
}
