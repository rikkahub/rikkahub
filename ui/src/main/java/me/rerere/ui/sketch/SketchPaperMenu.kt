package me.rerere.ui.sketch

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AspectRatio
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.ImageRemove01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.ui.R
import kotlin.math.abs

/**
 * 选画纸：空白画纸的比例，或者垫一张图片在上面画。垫了图片时画纸的比例跟着图片走。
 */
@Composable
internal fun SketchPaperMenu(state: SketchState, onChooseImage: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val hasImage = state.background != null

    @Composable
    fun RatioItem(text: String, ratio: Float?) {
        val current = state.aspectRatio
        val selected = !hasImage &&
            if (ratio == null || current == null) ratio == current else abs(ratio - current) < 0.01f
        DropdownMenuItem(
            text = { Text(text) },
            enabled = !hasImage,
            trailingIcon = if (selected) {
                { Icon(HugeIcons.Tick02, contentDescription = null) }
            } else {
                null
            },
            onClick = {
                expanded = false
                state.useAspectRatio(ratio)
            },
        )
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(HugeIcons.AspectRatio, contentDescription = stringResource(R.string.sketch_dialog_paper))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RatioItem(text = stringResource(R.string.sketch_dialog_paper_auto), ratio = null)
            SketchDefaults.AspectRatios.forEach { (width, height) ->
                RatioItem(text = "$width:$height", ratio = width.toFloat() / height)
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sketch_dialog_choose_image)) },
                leadingIcon = { Icon(HugeIcons.Image02, contentDescription = null) },
                onClick = {
                    expanded = false
                    onChooseImage()
                },
            )
            if (hasImage) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sketch_dialog_remove_image)) },
                    leadingIcon = { Icon(HugeIcons.ImageRemove01, contentDescription = null) },
                    onClick = {
                        expanded = false
                        state.useBackground(null)
                    },
                )
            }
        }
    }
}
