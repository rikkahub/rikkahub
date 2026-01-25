package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lightbulb
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Sparkles

/**
 * 推理链组件 (思维链)
 * 参考实现：https://github.com/haydenbleasel/ai-elements
 */
@Composable
fun ChainOfThought(
    modifier: Modifier = Modifier,
    defaultOpen: Boolean = false,
    content: @Composable ChainOfThoughtScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultOpen) }
    val scope = remember { ChainOfThoughtScopeImpl() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        ChainOfThoughtHeader(
            expanded = expanded,
            onExpandChange = { expanded = it }
        )

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                scope.content()
            }
        }
    }
}

@Composable
private fun ChainOfThoughtHeader(
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "rotation")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandChange(!expanded) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Lucide.Brain,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Chain of Thought",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        Icon(
            imageVector = Lucide.ChevronDown,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

interface ChainOfThoughtScope {
    /**
     * 推理步骤
     * @param icon 步骤图标
     * @param label 步骤描述文本
     * @param status 状态: complete, active, pending
     * @param content 步骤下的额外内容（如搜索结果、图片等）
     */
    @Composable
    fun ChainOfThoughtStep(
        icon: ImageVector? = null,
        label: String,
        status: String = "complete",
        content: (@Composable () -> Unit)? = null
    )

    /**
     * 搜索结果列表容器
     */
    @Composable
    fun ChainOfThoughtSearchResults(content: @Composable RowScope.() -> Unit)

    /**
     * 单个搜索结果项
     */
    @Composable
    fun ChainOfThoughtSearchResult(text: String)

    /**
     * 推理过程中生成的图片
     */
    @Composable
    fun ChainOfThoughtImage(
        url: String,
        caption: String? = null
    )
}

private class ChainOfThoughtScopeImpl : ChainOfThoughtScope {
    @Composable
    override fun ChainOfThoughtStep(
        icon: ImageVector?,
        label: String,
        status: String,
        content: (@Composable () -> Unit)?
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = 6.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(24.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (status == "active") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .size(6.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(50)
                            )
                    )
                }

                // 垂直连线：自适应内容高度
                if (content != null) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .width(1.dp)
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (status == "active")
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                if (content != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    content()
                }
            }
        }
    }

    @Composable
    override fun ChainOfThoughtSearchResults(content: @Composable RowScope.() -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }

    @Composable
    override fun ChainOfThoughtSearchResult(text: String) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }

    @Composable
    override fun ChainOfThoughtImage(url: String, caption: String?) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(8.dp)
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit
            )
            if (caption != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChainOfThoughtPreview() {
    MaterialTheme {
        Surface(
            modifier = Modifier.padding(16.dp)
        ) {
            ChainOfThought(defaultOpen = true) {
                ChainOfThoughtStep(
                    icon = Lucide.Lightbulb,
                    label = "Understanding the question",
                    status = "complete"
                )

                ChainOfThoughtStep(
                    icon = Lucide.Search,
                    label = "Searching for relevant information",
                    status = "complete"
                ) {
                    ChainOfThoughtSearchResults {
                        ChainOfThoughtSearchResult("Kotlin Docs")
                        ChainOfThoughtSearchResult("Android Guide")
                        ChainOfThoughtSearchResult("Stack Overflow")
                    }
                }

                ChainOfThoughtStep(
                    icon = Lucide.Sparkles,
                    label = "Analyzing and generating response",
                    status = "active"
                )

                ChainOfThoughtStep(
                    label = "Validating the answer",
                    status = "pending"
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Collapsed State")
@Composable
private fun ChainOfThoughtCollapsedPreview() {
    MaterialTheme {
        Surface(
            modifier = Modifier.padding(16.dp)
        ) {
            ChainOfThought(defaultOpen = false) {
                ChainOfThoughtStep(
                    icon = Lucide.Lightbulb,
                    label = "This content is hidden when collapsed",
                    status = "complete"
                )
            }
        }
    }
}
