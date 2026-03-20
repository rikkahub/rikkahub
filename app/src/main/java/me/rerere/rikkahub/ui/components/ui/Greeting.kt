package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import me.rerere.rikkahub.R
import java.util.Calendar

@Composable
fun Greeting(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    @Composable
    fun getGreetingMessage(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> stringResource(id = R.string.menu_page_morning_greeting)
            in 12..17 -> stringResource(id = R.string.menu_page_afternoon_greeting)
            in 18..22 -> stringResource(id = R.string.menu_page_evening_greeting)
            else -> stringResource(id = R.string.menu_page_night_greeting)
        }
    }

    Text(
        text = getGreetingMessage(),
        style = style,
        color = color,
        modifier = modifier
    )
}
