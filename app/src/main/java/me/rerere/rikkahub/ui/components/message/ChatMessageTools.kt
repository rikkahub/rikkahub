package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant

private const val ASK_USER_TOOL_NAME = "ask_user"
internal const val MAX_SAVED_ASK_USER_DRAFT_BYTES = 32 * 1024

typealias ToolApprovalHandler = (
    tool: UIMessagePart.Tool,
    approved: Boolean,
    reason: String,
) -> Job

typealias ToolAnswerHandler = (
    tool: UIMessagePart.Tool,
    answer: String,
) -> Job

internal class ToolApprovalSubmissionState {
    var isSubmitting by mutableStateOf(false)
        private set

    fun tryStart(): Boolean {
        if (isSubmitting) return false
        isSubmitting = true
        return true
    }

    fun finish() {
        isSubmitting = false
    }
}

internal class ApprovalStatusFeedbackState(initialMessage: String?) {
    private var previousMessage = initialMessage

    fun update(message: String?): Boolean {
        val shouldReject = message != null && message != previousMessage
        previousMessage = message
        return shouldReject
    }
}

@Serializable
internal data class AskUserAnswerDraft(
    val answers: Map<String, String> = emptyMap(),
    val multiAnswers: Map<String, Set<String>> = emptyMap(),
) {
    fun withAnswer(questionId: String, answer: String): AskUserAnswerDraft = copy(
        answers = answers + (questionId to answer),
    )

    fun toggleMultiAnswer(questionId: String, option: String): AskUserAnswerDraft {
        val updatedOptions = multiAnswers[questionId].orEmpty().toMutableSet().apply {
            if (!add(option)) remove(option)
        }
        val updatedAnswers = multiAnswers.toMutableMap().apply {
            if (updatedOptions.isEmpty()) remove(questionId) else put(questionId, updatedOptions)
        }
        return copy(multiAnswers = updatedAnswers)
    }
}

internal enum class AskUserResponseMode {
    Editing,
    Answered,
    ReadOnly,
}

internal data class AskUserResponseFrame(
    val mode: AskUserResponseMode,
    val answer: String? = null,
)

internal fun askUserResponseMode(
    approvalState: ToolApprovalState,
    hasAnswerHandler: Boolean,
): AskUserResponseMode = when {
    approvalState is ToolApprovalState.Pending && hasAnswerHandler -> AskUserResponseMode.Editing
    approvalState is ToolApprovalState.Answered -> AskUserResponseMode.Answered
    else -> AskUserResponseMode.ReadOnly
}

internal fun encodeAskUserAnswerDraft(draft: AskUserAnswerDraft): String =
    JsonInstant.encodeToString(draft)

internal fun decodeAskUserAnswerDraft(encoded: String): AskUserAnswerDraft = runCatching {
    JsonInstant.decodeFromString<AskUserAnswerDraft>(encoded)
}.getOrDefault(AskUserAnswerDraft())

internal fun encodeAskUserAnswerDraftForSave(draft: AskUserAnswerDraft): String? {
    val encoded = encodeAskUserAnswerDraft(draft)
    return encoded.takeIf { it.toByteArray().size <= MAX_SAVED_ASK_USER_DRAFT_BYTES }
}

private val AskUserAnswerDraftSaver = Saver<AskUserAnswerDraft, String>(
    save = { draft -> encodeAskUserAnswerDraftForSave(draft) },
    restore = { encoded -> decodeAskUserAnswerDraft(encoded) },
)

@Composable
internal fun rememberAskUserAnswerDraft(tool: UIMessagePart.Tool): MutableState<AskUserAnswerDraft> =
    rememberSaveable(
        tool.toolExecutionId,
        tool.toolCallId,
        tool.toolName,
        tool.input,
        stateSaver = AskUserAnswerDraftSaver,
    ) {
        mutableStateOf(AskUserAnswerDraft())
    }

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ToolApprovalHandler? = null,
    onToolAnswer: ToolAnswerHandler? = null,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val approvalStatusFeedback = remember(
        tool.toolExecutionId,
        tool.toolCallId,
        tool.toolName,
        tool.input,
    ) {
        ApprovalStatusFeedbackState(tool.approvalStatusMessage)
    }
    LaunchedEffect(tool.approvalStatusMessage) {
        if (approvalStatusFeedback.update(tool.approvalStatusMessage)) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }

    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = loading,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    var expanded by remember(tool.toolCallId) { mutableStateOf(isPending) }
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    val approvalSubmission = remember(
        tool.approvalId,
        tool.toolExecutionId,
        tool.toolCallId,
        tool.approvalState,
    ) {
        ToolApprovalSubmissionState()
    }
    val approvalScope = rememberCoroutineScope()

    fun submitApproval(approved: Boolean, reason: String) {
        val handler = onToolApproval ?: return
        if (!approvalSubmission.tryStart()) return
        val job = try {
            handler(tool, approved, reason)
        } catch (error: Throwable) {
            approvalSubmission.finish()
            throw error
        }
        approvalScope.launch {
            try {
                job.join()
            } finally {
                approvalSubmission.finish()
            }
        }
    }

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                ToolApprovalActions(
                    isSubmitting = approvalSubmission.isSubmitting,
                    onDeny = { showDenyDialog = true },
                    onApprove = { submitApproval(approved = true, reason = "") },
                )
            }
        } else {
            null
        },
        onClick = if (context.content != null || isPending || images.isNotEmpty()) {
            { showResult = true }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )
    ToolStatusMessage(
        message = tool.approvalStatusMessage,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 4.dp, bottom = 8.dp),
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                submitApproval(approved = false, reason = reason)
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                renderer.Preview(
                    context = context,
                    onDismissRequest = { showResult = false },
                )
            },
        )
    }
}

@Composable
internal fun ToolApprovalActions(
    isSubmitting: Boolean,
    onDeny: () -> Unit,
    onApprove: () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val submittingLabel = stringResource(R.string.chat_message_tool_approval_submitting)
    AnimatedContent(
        targetState = isSubmitting,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "tool_approval_actions",
    ) { submitting ->
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = submittingLabel },
                strokeWidth = 2.dp,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onDeny()
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.chat_message_tool_deny),
                        modifier = Modifier.size(14.dp),
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        onApprove()
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Tick01,
                        contentDescription = stringResource(R.string.chat_message_tool_approve),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToolAnswerSubmitButton(
    enabled: Boolean,
    isSubmitting: Boolean,
    onSubmit: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val submittingLabel = stringResource(R.string.chat_message_tool_answer_submitting)
    FilledTonalButton(
        onClick = {
            if (onSubmit()) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        },
        enabled = enabled && !isSubmitting,
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = isSubmitting,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tool_answer_submit",
        ) { submitting ->
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .semantics { contentDescription = submittingLabel },
                    strokeWidth = 2.dp,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = HugeIcons.Tick01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_message_tool_submit),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AskUserResponseTransition(
    targetFrame: AskUserResponseFrame,
    isSubmitting: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (frame: AskUserResponseFrame, interactionEnabled: Boolean) -> Unit,
) {
    AnimatedContent(
        targetState = targetFrame,
        modifier = modifier,
        transitionSpec = {
            when {
                initialState.mode == AskUserResponseMode.Editing &&
                    targetState.mode == AskUserResponseMode.Answered -> {
                    (slideInVertically { height -> height / 3 } + fadeIn()).togetherWith(
                        slideOutVertically { height -> -height / 3 } + fadeOut(),
                    )
                }
                initialState.mode == AskUserResponseMode.Answered &&
                    targetState.mode == AskUserResponseMode.Editing -> {
                    (slideInVertically { height -> -height / 3 } + fadeIn()).togetherWith(
                        slideOutVertically { height -> height / 3 } + fadeOut(),
                    )
                }
                else -> fadeIn() togetherWith fadeOut()
            }
        },
        label = "ask_user_response",
    ) { frame ->
        val interactionEnabled = targetFrame.mode == AskUserResponseMode.Editing && !isSubmitting
        content(frame, interactionEnabled)
    }
}

@Composable
internal fun ToolStatusMessage(
    message: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = message,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            when {
                initialState == null && targetState != null -> {
                    (expandVertically(expandFrom = Alignment.Top) + fadeIn()).togetherWith(fadeOut())
                }
                initialState != null && targetState == null -> {
                    fadeIn().togetherWith(
                        shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    )
                }
                else -> fadeIn() togetherWith fadeOut()
            }
        },
        label = "tool_status_message",
    ) { frameMessage ->
        if (frameMessage != null) {
            Text(
                text = frameMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ToolAnswerHandler?,
) {
    val arguments = tool.inputAsJson()
    val responseMode = askUserResponseMode(
        approvalState = tool.approvalState,
        hasAnswerHandler = onToolAnswer != null,
    )
    val answeredState = tool.approvalState as? ToolApprovalState.Answered
    val answeredValues = remember(answeredState?.answer) {
        runCatching {
            JsonInstant.parseToJsonElement(answeredState?.answer.orEmpty())
                .jsonObject["answers"]
                ?.jsonObject
                ?.mapValues { (_, value) -> value.jsonPrimitive.content }
        }.getOrNull()
    }

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    var draft by rememberAskUserAnswerDraft(tool)
    val answerSubmission = remember(
        tool.toolExecutionId,
        tool.toolCallId,
        tool.toolName,
        tool.input,
        tool.approvalState,
    ) {
        ToolApprovalSubmissionState()
    }
    val answerScope = rememberCoroutineScope()

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

                        val responseFrame = AskUserResponseFrame(
                            mode = responseMode,
                            answer = answeredValues?.get(q.id) ?: answeredState?.answer,
                        )
                        AskUserResponseTransition(
                            targetFrame = responseFrame,
                            isSubmitting = answerSubmission.isSubmitting,
                        ) { frame, interactionEnabled ->
                            when (frame.mode) {
                                AskUserResponseMode.Editing -> when (q.selectionType) {
                                    "single" -> {
                                        if (q.options.isNotEmpty()) {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                q.options.forEach { option ->
                                                    FilterChip(
                                                        selected = draft.answers[q.id] == option,
                                                        onClick = { draft = draft.withAnswer(q.id, option) },
                                                        enabled = interactionEnabled,
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
                                        if (q.options.isNotEmpty()) {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                q.options.forEach { option ->
                                                    val selectedSet = draft.multiAnswers[q.id].orEmpty()
                                                    FilterChip(
                                                        selected = selectedSet.contains(option),
                                                        onClick = { draft = draft.toggleMultiAnswer(q.id, option) },
                                                        enabled = interactionEnabled,
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
                                        if (q.options.isNotEmpty()) {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                q.options.forEach { option ->
                                                    FilterChip(
                                                        selected = draft.answers[q.id] == option,
                                                        onClick = { draft = draft.withAnswer(q.id, option) },
                                                        enabled = interactionEnabled,
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
                                            value = draft.answers[q.id].orEmpty(),
                                            onValueChange = { draft = draft.withAnswer(q.id, it) },
                                            enabled = interactionEnabled,
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodySmall,
                                            singleLine = false,
                                            minLines = 1,
                                            maxLines = 3,
                                        )
                                    }
                                }
                                AskUserResponseMode.Answered -> Text(
                                    text = frame.answer.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                AskUserResponseMode.ReadOnly -> Unit
                            }
                        }
                    }
                }

                // Submit button
                val answersComplete = questions.all { q ->
                    when (q.selectionType) {
                        "multi" -> !draft.multiAnswers[q.id].isNullOrEmpty()
                        else -> !draft.answers[q.id].isNullOrBlank()
                    }
                }
                AnimatedVisibility(
                    visible = responseMode == AskUserResponseMode.Editing,
                    modifier = Modifier.align(Alignment.End),
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    label = "ask_user_submit",
                ) {
                    ToolAnswerSubmitButton(
                        onSubmit = submit@{
                            val answerHandler = onToolAnswer ?: return@submit false
                            if (!answerSubmission.tryStart()) return@submit false
                            val answerPayload = buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        when (q.selectionType) {
                                            "multi" -> put(
                                                q.id,
                                                JsonPrimitive(
                                                    draft.multiAnswers[q.id]?.joinToString(", ").orEmpty(),
                                                ),
                                            )
                                            else -> put(q.id, JsonPrimitive(draft.answers[q.id].orEmpty()))
                                        }
                                    }
                                })
                            }
                            val job = try {
                                answerHandler(tool, answerPayload.toString())
                            } catch (error: Throwable) {
                                answerSubmission.finish()
                                throw error
                            }
                            answerScope.launch {
                                try {
                                    job.join()
                                } finally {
                                    answerSubmission.finish()
                                }
                            }
                            true
                        },
                        enabled = answersComplete && responseMode == AskUserResponseMode.Editing,
                        isSubmitting = answerSubmission.isSubmitting,
                    )
                }
            }
        },
    )
    ToolStatusMessage(
        message = tool.approvalStatusMessage,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 4.dp, bottom = 8.dp),
    )
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
