package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val canAnswer = tool.approvalState is ToolApprovalState.Pending && onToolAnswer != null
    val answeredState = tool.approvalState as? ToolApprovalState.Answered
    val answeredAnswers = remember(answeredState?.answer) {
        answeredState?.answer?.let(::parseAskUserAnswers)
    }

    val toolKey = tool.toolCallId.ifBlank { tool.hashCode().toString() }
    val arguments = tool.inputAsJson()
    val questions = remember(arguments) { parseAskUserQuestions(arguments) }

    val answers = remember(toolKey) { mutableStateMapOf<String, String>() }
    val expandedQuestions = remember(toolKey) { mutableStateMapOf<String, Boolean>() }
    var freeText by remember(toolKey) { mutableStateOf("") }
    var stepExpanded by remember(toolKey) { mutableStateOf(true) }

    val emptyQuestionText = stringResource(R.string.chat_message_tool_empty_question)
    val askUserTitle = stringResource(R.string.assistant_page_local_tools_ask_user_title)
    val labelText = when {
        questions.isEmpty() -> askUserTitle
        questions.size == 1 -> questions.first().question.ifBlank { emptyQuestionText }
        else -> stringResource(R.string.chat_message_tool_ask_questions, questions.size)
    }

    ControlledChainOfThoughtStep(
        expanded = stepExpanded,
        onExpandedChange = { stepExpanded = it },
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
                text = labelText,
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
                if (questions.isEmpty()) {
                    AskUserFallbackContent(
                        toolInput = tool.input,
                        canAnswer = canAnswer,
                        freeText = freeText,
                        onFreeTextChange = { freeText = it },
                        answeredText = answeredAnswers?.get("free_text") ?: answeredState?.answer,
                        onSubmit = {
                            onToolAnswer?.invoke(
                                tool.toolCallId,
                                buildAskUserAnswerPayload(mapOf("free_text" to freeText))
                            )
                        },
                    )
                } else {
                    AskUserQuestionsContent(
                        questions = questions,
                        emptyQuestionText = emptyQuestionText,
                        canAnswer = canAnswer,
                        answers = answers,
                        expandedQuestions = expandedQuestions,
                        answeredAnswers = answeredAnswers,
                        answeredFallback = answeredState?.answer,
                        onSubmit = {
                            val payload = buildAskUserAnswerPayload(
                                questions.associate { q -> q.id to (answers[q.id] ?: "") }
                            )
                            onToolAnswer?.invoke(tool.toolCallId, payload)
                        },
                    )
                }
            }
        },
    )
}

private fun parseAskUserQuestions(arguments: JsonElement): List<AskUserQuestion> {
    val root = arguments as? JsonObject ?: return emptyList()
    val questions = root["questions"] as? JsonArray ?: return emptyList()
    return questions.mapIndexedNotNull { index, item ->
        val obj = item as? JsonObject ?: return@mapIndexedNotNull null
        val rawId = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        AskUserQuestion(
            id = rawId.ifBlank { "q_${index + 1}" },
            question = (obj["question"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim(),
            options = (obj["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        )
    }
}

private fun parseAskUserAnswers(answerRaw: String): Map<String, String>? {
    val root = runCatching {
        JsonInstant.parseToJsonElement(answerRaw)
    }.getOrNull() as? JsonObject ?: return null
    val answers = root["answers"] as? JsonObject ?: return null
    return answers.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
    }
}

private fun buildAskUserAnswerPayload(answers: Map<String, String>): String {
    return buildJsonObject {
        put("answers", buildJsonObject {
            answers.forEach { (id, answer) ->
                put(id, JsonPrimitive(answer))
            }
        })
    }.toString()
}

@Composable
private fun ColumnScope.AskUserFallbackContent(
    toolInput: String,
    canAnswer: Boolean,
    freeText: String,
    onFreeTextChange: (String) -> Unit,
    answeredText: String?,
    onSubmit: () -> Unit,
) {
    val rawInput = toolInput.ifBlank { "{}" }
    val inputPreview = remember(rawInput) { formatJsonOrRaw(rawInput) }

    HighlightCodeBlock(
        code = inputPreview,
        language = "json",
        style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
    )

    if (canAnswer) {
        OutlinedTextField(
            value = freeText,
            onValueChange = onFreeTextChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = false,
            minLines = 2,
            maxLines = 6,
        )
        AskUserSubmitButton(
            enabled = freeText.isNotBlank(),
            onClick = onSubmit,
            modifier = Modifier.align(Alignment.End),
        )
    } else if (!answeredText.isNullOrBlank()) {
        Text(
            text = answeredText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.AskUserQuestionsContent(
    questions: List<AskUserQuestion>,
    emptyQuestionText: String,
    canAnswer: Boolean,
    answers: MutableMap<String, String>,
    expandedQuestions: MutableMap<String, Boolean>,
    answeredAnswers: Map<String, String>?,
    answeredFallback: String?,
    onSubmit: () -> Unit,
) {
    questions.forEach { question ->
        val answer = answers[question.id].orEmpty()
        val isExpanded = expandedQuestions[question.id] == true
        val answeredText = answeredAnswers?.get(question.id) ?: answeredFallback

        AskUserQuestionItem(
            question = question,
            emptyQuestionText = emptyQuestionText,
            canAnswer = canAnswer,
            answer = answer,
            onAnswerChange = { answers[question.id] = it },
            expanded = isExpanded,
            onExpandedChange = { expandedQuestions[question.id] = it },
            answeredText = answeredText,
        )
    }

    if (canAnswer) {
        AskUserSubmitButton(
            enabled = questions.all { q -> !answers[q.id].isNullOrBlank() },
            onClick = onSubmit,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskUserQuestionItem(
    question: AskUserQuestion,
    emptyQuestionText: String,
    canAnswer: Boolean,
    answer: String,
    onAnswerChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    answeredText: String?,
) {
    val questionText = question.question.ifBlank { emptyQuestionText }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = questionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) },
        )

        if (canAnswer) {
            if (question.options.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    question.options.forEach { option ->
                        FilterChip(
                            selected = answer == option,
                            onClick = { onAnswerChange(option) },
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

            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = false,
                minLines = 1,
                maxLines = 3,
            )
        } else if (!answeredText.isNullOrBlank()) {
            Text(
                text = answeredText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AskUserSubmitButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
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

private fun formatJsonOrRaw(raw: String): String {
    return runCatching {
        JsonInstantPretty.encodeToString(
            JsonInstant.parseToJsonElement(raw)
        )
    }.getOrElse { raw }
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
)

