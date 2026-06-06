package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.modifier.shimmer

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // Parse questions from arguments
    val questions = remember(arguments) {
        parseAskUserQuestions(arguments)
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            when (q.selectionType) {
                                "single" -> {
                                    // Single select: chips only, no text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                "multi" -> {
                                    // Multi select: chips only, multiple can be selected
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                val selectedSet = multiAnswers[q.id] ?: emptySet()
                                                FilterChip(
                                                    selected = selectedSet.contains(option),
                                                    onClick = {
                                                        val current = selectedSet.toMutableSet()
                                                        if (current.contains(option)) current.remove(option)
                                                        else current.add(option)
                                                        multiAnswers[q.id] = current
                                                    },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    // Text (default): optional option chips + free text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // Free text input
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = { answers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                    )
                                }
                            }
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerText = extractAskUserAnsweredText(answeredState.answer, q.id)
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button
                if (isPending && onToolAnswer != null) {
                    FilledTonalButton(
                        onClick = {
                            onToolAnswer(
                                tool.toolCallId,
                                buildAskUserAnswerPayload(questions, answers, multiAnswers)
                            )
                        },
                        enabled = questions.all { q ->
                            when (q.selectionType) {
                                "multi" -> !multiAnswers[q.id].isNullOrEmpty()
                                else -> !answers[q.id].isNullOrBlank()
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_message_tool_submit),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
    )
}
