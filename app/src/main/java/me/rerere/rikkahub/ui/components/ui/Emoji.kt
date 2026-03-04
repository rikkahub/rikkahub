package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Search01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.Emoji
import me.rerere.rikkahub.utils.EmojiData
import org.koin.compose.koinInject

@Preview
@Composable
fun EmojiPicker(
    modifier: Modifier = Modifier,
    onEmojiSelected: (Emoji) -> Unit = {},
    showSearch: Boolean = true,
    height: Int = 400
) {
    val emojiData = koinInject<EmojiData>()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    var showModifierPicker by remember { mutableStateOf(false) }
    var selectedEmojiForModifier by remember { mutableStateOf<Emoji?>(null) }
    var modifierVariants by remember { mutableStateOf<List<Emoji>>(emptyList()) }

    val lazyListState: LazyGridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    emojiData.let { data ->
        Box(modifier = modifier) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(8.dp)
            ) {
                // Search bar
                if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.emoji_picker_search_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = HugeIcons.Search01,
                                contentDescription = "Search"
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { /* Handle search */ }
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(50)
                    )
                }

                // Category tabs
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(data.categories.size) { index ->
                        val category = data.categories[index]
                        val isSelected = selectedCategoryIndex == index

                        Card(
                            modifier = Modifier
                                .clickable {
                                    selectedCategoryIndex = index
                                    coroutineScope.launch {
                                        lazyListState.animateScrollToItem(0)
                                    }
                                }
                                .clip(RoundedCornerShape(20.dp)),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Text(
                                text = when (category.name) {
                                    "Smileys & Emotion" -> "\uD83D\uDE03"
                                    "People & Body" -> "\uD83D\uDC64"
                                    "Component" -> "\uD83E\uDDF4"
                                    "Animals & Nature" -> "\uD83D\uDC3B"
                                    "Food & Drink" -> "\uD83C\uDF5B"
                                    "Travel & Places" -> "\uD83C\uDF04"
                                    "Activities" -> "\uD83C\uDFA3"
                                    "Objects" -> "\uD83D\uDCBB"
                                    "Symbols" -> "\uD83C\uDF00"
                                    "Flags" -> "\uD83D\uDEA9"
                                    else -> category.name
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }

                // Emoji grid
                val selectedCategory = data.categories[selectedCategoryIndex]
                val emojiVariants = remember(selectedCategoryIndex, data) {
                    selectedCategory.getEmojiVariants()
                }

                val filteredEmojiVariants = remember(searchQuery, emojiVariants) {
                    if (searchQuery.isBlank()) {
                        emojiVariants
                    } else {
                        emojiVariants.filter { (baseEmoji, variants) ->
                            variants.any { emoji ->
                                emoji.name.contains(searchQuery, ignoreCase = true) ||
                                    emoji.emoji.contains(searchQuery)
                            }
                        }
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 40.dp),
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredEmojiVariants.toList(), key = { it.first.emoji }) { (baseEmoji, variants) ->
                        EmojiItem(
                            emoji = baseEmoji,
                            hasVariants = variants.size > 1,
                            onClick = {
                                onEmojiSelected(baseEmoji)
                            },
                            onLongClick = if (variants.size > 1) {
                                {
                                    selectedEmojiForModifier = baseEmoji
                                    modifierVariants = variants
                                    showModifierPicker = true
                                }
                            } else null
                        )
                    }
                }
            }

            // Modifier picker popup
            if (showModifierPicker && selectedEmojiForModifier != null) {
                EmojiModifierPicker(
                    variants = modifierVariants,
                    onEmojiSelected = { emoji ->
                        onEmojiSelected(emoji)
                        showModifierPicker = false
                        selectedEmojiForModifier = null
                    },
                    onDismiss = {
                        showModifierPicker = false
                        selectedEmojiForModifier = null
                    }
                )
            }
        }
    }
}

@Composable
private fun EmojiItem(
    emoji: Emoji,
    hasVariants: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .clip(RoundedCornerShape(8.dp))
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (hasVariants) 0.5f else 0.3f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji.emoji,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmojiModifierPicker(
    variants: List<Emoji>,
    onEmojiSelected: (Emoji) -> Unit,
    onDismiss: () -> Unit
) {
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .wrapContentSize()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.emoji_picker_select_skin_tone),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(variants) { variant ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { onEmojiSelected(variant) }
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = variant.emoji,
                                fontSize = 24.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
