package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ModelType
import me.rerere.ai.runtime.hooks.HookConfig
import me.rerere.ai.runtime.hooks.HookDecision
import me.rerere.ai.runtime.hooks.HookEvent
import me.rerere.ai.runtime.hooks.HookExecutor
import me.rerere.ai.runtime.hooks.HookHandler
import me.rerere.ai.runtime.hooks.HookMatcher
import me.rerere.ai.runtime.hooks.HookOutput
import me.rerere.ai.runtime.hooks.HookOutputParseResult
import me.rerere.ai.runtime.hooks.parseHookOutput
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.rikkahub.data.ai.hooks.LlmHookExecutor
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.coroutines.cancellation.CancellationException

// ---------------------------------------------------------------------------------------------
// Pure editor/trust logic (unit-tested on the JVM; the composables below are a thin shell)
// ---------------------------------------------------------------------------------------------

/** Imported/restored hooks (H4) wait here until the user reviews them — the editor stays locked. */
internal fun HookConfig.requiresTrustReview(): Boolean =
    !trusted && hooks.values.any { it.isNotEmpty() }

/**
 * Adds a hook the user just authored. Authoring grants trust — the user IS the reviewer of their
 * own hook — but must never piggyback trust onto unreviewed imported hooks, hence the guard.
 */
internal fun HookConfig.withAuthoredHook(event: HookEvent, entry: HookMatcher): HookConfig {
    require(!requiresTrustReview()) { "imported hooks must be reviewed before authoring new ones" }
    return copy(hooks = hooks + (event to (hooks[event].orEmpty() + entry)), trusted = true)
}

internal fun HookConfig.withUpdatedHook(event: HookEvent, index: Int, entry: HookMatcher): HookConfig {
    require(!requiresTrustReview()) { "imported hooks must be reviewed before editing" }
    val entries = hooks[event].orEmpty().toMutableList().also { it[index] = entry }
    return copy(hooks = hooks + (event to entries.toList()), trusted = true)
}

internal fun HookConfig.withRemovedHook(event: HookEvent, index: Int): HookConfig {
    val remaining = hooks[event].orEmpty().filterIndexed { i, _ -> i != index }
    return copy(hooks = if (remaining.isEmpty()) hooks - event else hooks + (event to remaining))
}

/** The explicit grant action of the import-trust review flow (H4). */
internal fun HookConfig.withGrantedTrust(): HookConfig = copy(trusted = true)

/** Representative payload per event, mirroring the real fire-point shapes (ChatTurnRuntime/ChatService). */
internal fun sampleHookInput(event: HookEvent, toolName: String?): String = buildJsonObject {
    put("hookEventName", event.name)
    when (event) {
        HookEvent.PreToolUse -> {
            put("toolName", toolName ?: "example_tool")
            put("toolInput", """{"example":"input"}""")
        }

        HookEvent.UserPromptSubmit -> put("prompt", "This is a test prompt.")
        HookEvent.Stop -> put("lastAssistantMessage", "This is a test assistant message.")
    }
}.toString()

/**
 * Runs one handler once against a sample payload and renders the parsed result — exactly what the
 * real dispatch would see, including parse failures and event-spoof rejection. Failures are
 * values: the page shows them, it never crashes.
 */
internal suspend fun runHookTest(
    executor: HookExecutor,
    event: HookEvent,
    handler: HookHandler,
    toolName: String?,
): String {
    val raw = try {
        executor.execute(event, handler, sampleHookInput(event, toolName))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return "Hook failed: ${e.message ?: e::class.simpleName}"
    }
    return when (val parsed = parseHookOutput(raw, event)) {
        is HookOutputParseResult.Parsed -> describeHookOutput(parsed.output)
        is HookOutputParseResult.Failure -> "Output rejected: ${parsed.detail}\n\nRaw output:\n$raw"
    }
}

private fun describeHookOutput(output: HookOutput): String = buildString {
    appendLine(
        "Decision: " + when (val decision = output.decision) {
            HookDecision.Allow -> "allow"
            HookDecision.Ask -> "ask"
            is HookDecision.Deny -> "deny — ${decision.reason}"
        }
    )
    output.updatedInput?.let { appendLine("Updated input: $it") }
    output.additionalContext?.let { appendLine("Additional context: $it") }
    if (output.preventContinuation) appendLine("Prevents continuation")
}.trim()

private data class HookRow(val event: HookEvent, val index: Int, val entry: HookMatcher)

private fun HookConfig.rows(): List<HookRow> = HookEvent.entries.flatMap { event ->
    hooks[event].orEmpty().mapIndexed { index, entry -> HookRow(event, index, entry) }
}

private fun HookMatcher.llmHandler(): HookHandler.Llm? =
    handlers.filterIsInstance<HookHandler.Llm>().firstOrNull()

// ---------------------------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------------------------

@Composable
fun AssistantHooksPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hooks = assistant.hooks
    val needsReview = hooks.requiresTrustReview()
    var editing by remember { mutableStateOf<HookRow?>(null) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hooks") },
                navigationIcon = { BackButton() },
                actions = {
                    if (!needsReview) {
                        IconButton(onClick = { adding = true }) {
                            Icon(HugeIcons.Add01, contentDescription = "Add hook")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (needsReview) {
            HookTrustReview(
                hooks = hooks,
                settings = settings,
                onGrant = { vm.update(assistant.copy(hooks = hooks.withGrantedTrust())) },
                onDiscard = { vm.update(assistant.copy(hooks = HookConfig())) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            HookEditorList(
                hooks = hooks,
                onDelete = { row -> vm.update(assistant.copy(hooks = hooks.withRemovedHook(row.event, row.index))) },
                onEdit = { row -> editing = row },
                contentPadding = innerPadding,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (adding) {
        HookEditDialog(
            title = "Add hook",
            initialEvent = HookEvent.PreToolUse,
            initialEntry = null,
            settings = settings,
            onDismiss = { adding = false },
            onSave = { event, entry ->
                vm.update(assistant.copy(hooks = hooks.withAuthoredHook(event, entry)))
                adding = false
            },
        )
    }

    editing?.let { row ->
        HookEditDialog(
            title = "Edit hook",
            initialEvent = row.event,
            initialEntry = row.entry,
            settings = settings,
            onDismiss = { editing = null },
            onSave = { event, entry ->
                val updated = if (event == row.event) {
                    hooks.withUpdatedHook(row.event, row.index, entry)
                } else {
                    hooks.withRemovedHook(row.event, row.index).withAuthoredHook(event, entry)
                }
                vm.update(assistant.copy(hooks = updated))
                editing = null
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Editor (trusted or empty config)
// ---------------------------------------------------------------------------------------------

@Composable
private fun HookEditorList(
    hooks: HookConfig,
    onDelete: (HookRow) -> Unit,
    onEdit: (HookRow) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val rows = hooks.rows()
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (rows.isEmpty()) {
            item {
                Text(
                    text = "No hooks configured. Hooks gate and shape the agent loop: " +
                        "they can deny or rewrite a tool call before approval, or inject context " +
                        "when you send a prompt or a turn ends.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        itemsIndexed(rows) { _, row ->
            HookCard(
                row = row,
                onDelete = { onDelete(row) },
                onEdit = { onEdit(row) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun HookCard(
    row: HookRow,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handler = row.entry.llmHandler() ?: return
    val executor: LlmHookExecutor = koinInject()
    val scope = rememberCoroutineScope()
    var testRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.event.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = row.entry.matcher?.let { "matcher: $it" } ?: "matches all tools",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = handler.prompt,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (handler.failClosed) {
                Text(
                    text = "fail-closed: errors deny the tool",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = !testRunning,
                    onClick = {
                        testRunning = true
                        scope.launch {
                            try {
                                testResult = runHookTest(
                                    executor = executor,
                                    event = row.event,
                                    handler = handler,
                                    toolName = row.entry.matcher,
                                )
                            } finally {
                                testRunning = false
                            }
                        }
                    },
                ) {
                    Text(if (testRunning) "Testing…" else "Test")
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }

    testResult?.let { result ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text("Test result") },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = { testResult = null }) { Text("Close") }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Import-trust review surface (H4)
// ---------------------------------------------------------------------------------------------

@Composable
private fun HookTrustReview(
    hooks: HookConfig,
    settings: Settings,
    onGrant: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(
                    text = "This assistant arrived with hooks attached. They are disabled and will " +
                        "not run until you review each one below and grant trust.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        itemsIndexed(hooks.rows()) { _, row ->
            val handler = row.entry.llmHandler() ?: return@itemsIndexed
            val model = settings.findModelById(handler.model, fallback = settings.fastModelId)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = row.event.name +
                            (row.entry.matcher?.let { " · matcher: $it" } ?: " · matches all tools"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = "Prompt:", style = MaterialTheme.typography.labelMedium)
                    Text(text = handler.prompt, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(
                        text = "Target model: " + (model?.displayName?.ifBlank { model.modelId }
                            ?: "fast model (not configured)"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = "Privilege: llm — sends event data to the model provider (medium)" +
                            if (handler.failClosed) " · fail-closed" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                    Text("Discard hooks")
                }
                Button(onClick = onGrant, modifier = Modifier.weight(1f)) {
                    Text("Trust & enable")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Add / edit dialog
// ---------------------------------------------------------------------------------------------

@Composable
private fun HookEditDialog(
    title: String,
    initialEvent: HookEvent,
    initialEntry: HookMatcher?,
    settings: Settings,
    onDismiss: () -> Unit,
    onSave: (HookEvent, HookMatcher) -> Unit,
) {
    val initialHandler = initialEntry?.llmHandler()
    var event by remember { mutableStateOf(initialEvent) }
    var matcher by remember { mutableStateOf(initialEntry?.matcher.orEmpty()) }
    var prompt by remember { mutableStateOf(initialHandler?.prompt.orEmpty()) }
    var model by remember { mutableStateOf(initialHandler?.model) }
    var failClosed by remember { mutableStateOf(initialHandler?.failClosed ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HookEvent.entries.forEach { candidate ->
                        FilterChip(
                            selected = event == candidate,
                            onClick = { event = candidate },
                            label = { Text(candidate.name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                if (event == HookEvent.PreToolUse) {
                    OutlinedTextField(
                        value = matcher,
                        onValueChange = { matcher = it },
                        label = { Text("Tool matcher (empty = all tools, exact name or regex)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Hook prompt") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    ModelSelector(
                        modelId = model ?: settings.fastModelId,
                        providers = settings.providers,
                        type = ModelType.CHAT,
                        onSelect = { model = it.id },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Fail closed", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "Errors and timeouts deny the tool instead of allowing it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = failClosed, onCheckedChange = { failClosed = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = prompt.isNotBlank(),
                onClick = {
                    onSave(
                        event,
                        HookMatcher(
                            matcher = matcher.trim().takeIf { it.isNotEmpty() && event == HookEvent.PreToolUse },
                            handlers = listOf(
                                HookHandler.Llm(prompt = prompt.trim(), model = model, failClosed = failClosed)
                            ),
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
