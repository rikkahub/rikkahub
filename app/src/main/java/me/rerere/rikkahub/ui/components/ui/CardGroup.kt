package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed

private val CardGroupCorner = 12.dp
private val CardGroupItemSpacing = 4.dp
private val CardGroupInnerCorner = 2.dp

data class CardGroupItem(
    val onClick: (() -> Unit)?,
    val content: @Composable () -> Unit,
)

class CardGroupScope {
    internal val items = mutableListOf<CardGroupItem>()

    fun item(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
        items.add(CardGroupItem(onClick = onClick, content = content))
    }
}

@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScope()
    scope.content()

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                    Box(modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        title()
                    }
                }
            }
        }
        val count = scope.items.size
        scope.items.fastForEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == count - 1
            val shape = when {
                count == 1 -> RoundedCornerShape(CardGroupCorner)
                isFirst -> RoundedCornerShape(
                    topStart = CardGroupCorner,
                    topEnd = CardGroupCorner,
                    bottomStart = CardGroupInnerCorner,
                    bottomEnd = CardGroupInnerCorner,
                )

                isLast -> RoundedCornerShape(
                    topStart = CardGroupInnerCorner,
                    topEnd = CardGroupInnerCorner,
                    bottomStart = CardGroupCorner,
                    bottomEnd = CardGroupCorner,
                )

                else -> RoundedCornerShape(CardGroupInnerCorner)
            }

            if (item.onClick != null) {
                Surface(
                    onClick = item.onClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    item.content()
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    item.content()
                }
            }

            if (!isLast) {
                Spacer(modifier = Modifier.height(CardGroupItemSpacing))
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CardGroupPreview() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Card Group")
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            CardGroup(
                modifier = Modifier
                    .padding(horizontal = 16.dp),
                title = { Text("分组标题") },
            ) {
                item {
                    Text("第一项", modifier = Modifier.padding(16.dp))
                }
                item {
                    Text("第二项", modifier = Modifier.padding(16.dp))
                }
                item(
                    onClick = {

                    }
                ) {
                    Text("第三项", modifier = Modifier.padding(16.dp))
                }
            }
            CardGroup(
                modifier = Modifier
                    .padding(horizontal = 16.dp),
                title = { Text("分组标题") },
            ) {
                item {
                    Text("第一项", modifier = Modifier.padding(16.dp))
                }
                item {
                    Text("第二项", modifier = Modifier.padding(16.dp))
                }
                item(
                    onClick = {

                    }
                ) {
                    Text("第三项", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
