package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PenLine
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.Greeting

data class WelcomeSuggestion(
    val icon: @Composable () -> Unit,
    val title: String,
    val prompt: String
)

@Composable
fun ChatWelcome(
    modifier: Modifier = Modifier,
    onClickSuggestion: (String) -> Unit
) {
    val context = LocalContext.current
    val defaultSuggestions = remember(context) {
        listOf(
            WelcomeSuggestion(
                icon = { Icon(Lucide.PenLine, contentDescription = null, modifier = Modifier.size(20.dp)) },
                title = context.getString(R.string.chat_welcome_suggestion_writing_title),
                prompt = context.getString(R.string.chat_welcome_suggestion_writing_prompt)
            ),
            WelcomeSuggestion(
                icon = { Icon(Lucide.Code, contentDescription = null, modifier = Modifier.size(20.dp)) },
                title = context.getString(R.string.chat_welcome_suggestion_programming_title),
                prompt = context.getString(R.string.chat_welcome_suggestion_programming_prompt)
            ),
            WelcomeSuggestion(
                icon = { Icon(Lucide.Lightbulb, contentDescription = null, modifier = Modifier.size(20.dp)) },
                title = context.getString(R.string.chat_welcome_suggestion_brainstorm_title),
                prompt = context.getString(R.string.chat_welcome_suggestion_brainstorm_prompt)
            ),
            WelcomeSuggestion(
                icon = { Icon(Lucide.BookOpen, contentDescription = null, modifier = Modifier.size(20.dp)) },
                title = context.getString(R.string.chat_welcome_suggestion_explain_title),
                prompt = context.getString(R.string.chat_welcome_suggestion_explain_prompt)
            )
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Greeting(
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Suggestion Cards
        FlowRow(
            modifier = Modifier.widthIn(max = 600.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2
        ) {
            defaultSuggestions.forEach { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    onClick = { onClickSuggestion(suggestion.prompt) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: WelcomeSuggestion,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            suggestion.icon()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
