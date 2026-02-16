package me.rerere.rikkahub.ui.pages.favorite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

@Composable
fun FavoritePage(vm: FavoriteVM = koinViewModel()) {
    val navController = LocalNavController.current
    val favorites = vm.nodeFavorites.collectAsStateWithLifecycle().value

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton()
                },
                title = {
                    Text("Favorites")
                },
            )
        },
    ) { innerPadding ->
        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No favorites yet",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(favorites, key = { it.id }) { item ->
                Card(
                    onClick = {
                        navigateToChatPage(navController, item.conversationId)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.ListItem(
                        leadingContent = {
                            Icon(Lucide.Heart, contentDescription = null)
                        },
                        headlineContent = {
                            Text(
                                text = item.conversationTitle.ifBlank { "Untitled Conversation" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            val dateText = Instant.ofEpochMilli(item.createdAt).toLocalDateTime()
                            Text(
                                text = listOfNotNull(
                                    item.preview.takeIf { it.isNotBlank() },
                                    dateText
                                ).joinToString("\n"),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.removeFavorite(item.refKey) }) {
                                Icon(Lucide.Trash2, contentDescription = "Remove")
                            }
                        }
                    )
                }
            }
        }
    }
}
