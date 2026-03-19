package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.components.ui.LuneBackdrop

@Composable
fun AssistantBackground(setting: Settings) {
    val assistant = setting.getCurrentAssistant()
    val backgroundColor = MaterialTheme.colorScheme.background
    val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
    val backgroundBlur = assistant.backgroundBlur.coerceIn(0f, 40f)

    Box {
        if (assistant.background != null) {
            AsyncImage(
                model = assistant.background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(
                        radius = backgroundBlur.dp,
                        edgeTreatment = BlurredEdgeTreatment.Rectangle
                    )
                    .alpha(backgroundOpacity * 0.72f)
            )
        }

        LuneBackdrop(modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0.05f),
                            backgroundColor.copy(alpha = 0.28f),
                            backgroundColor.copy(alpha = 0.44f)
                        )
                    )
                )
        )
    }
}
