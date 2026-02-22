package me.rerere.rikkahub.ui.components.ui

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.R

@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                val avd = AppCompatResources.getDrawable(context, R.drawable.rabbit) as? AnimatedVectorDrawable
                setImageDrawable(avd)
                avd?.start()
            }
        }
    )
}
