package me.rerere.rikkahub.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

const val LUNE_SHORT_MOTION_MS = 220
const val LUNE_MEDIUM_MOTION_MS = 280
const val LUNE_LONG_MOTION_MS = 360

fun luneFadeSpec(durationMillis: Int = LUNE_MEDIUM_MOTION_MS): FiniteAnimationSpec<Float> =
    tween(durationMillis = durationMillis)

fun luneFloatFadeSpec(durationMillis: Int = LUNE_SHORT_MOTION_MS): FiniteAnimationSpec<Float> =
    tween(durationMillis = durationMillis)

fun luneSpatialSpring(): FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = 0.9f,
    stiffness = Spring.StiffnessMediumLow,
)

fun luneSizeSpring(): FiniteAnimationSpec<IntSize> = spring(
    dampingRatio = 0.92f,
    stiffness = Spring.StiffnessMedium,
)
