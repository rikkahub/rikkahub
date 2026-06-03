package me.rerere.rikkahub.ui.pages.knowledge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Database
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File01
import me.rerere.rikkahub.data.rag.KnowledgeBase
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun KnowledgeBasePage(vm: KnowledgeBaseVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.ingestProgress.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current

    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(event) {
        when (val e = event) {
            is KnowledgeBaseVM.Event.IngestDone -> {
                toaster.show("Added \"${e.fileName}\" (${e.chunkCount} chunks)", type = ToastType.Success)
                vm.consumeEvent()
            }

            is KnowledgeBaseVM.Event.IngestFailed -> {
                toaster.show(e.reason, type = ToastType.Error)
                vm.consumeEvent()
            }

            null -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Base") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(HugeIcons.Add01, contentDescription = "Create knowledge base")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings.knowledgeBases.isEmpty()) {
                item {
                    Text(
                        "No knowledge bases yet. Tap + to create one, then add documents to enable retrieval-augmented answers.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(settings.knowledgeBases, key = { it.id.toString() }) { kb ->
                KnowledgeBaseCard(
                    kb = kb,
                    providers = settings.providers,
                    ingesting = progress.containsKey(kb.id),
                    progress = progress[kb.id] ?: 0f,
                    onEmbeddingModelChange = { modelId ->
                        vm.updateKnowledgeBase(kb.copy(embeddingModelId = modelId))
                    },
                    onAddDocument = { uri -> vm.ingest(kb.id, uri) },
                    onDeleteDocument = { docId -> vm.deleteDocument(kb.id, docId) },
                    onDelete = { vm.deleteKnowledgeBase(kb) },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateKnowledgeBaseDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, modelId ->
                vm.createKnowledgeBase(name, modelId)
                showCreateDialog = false
            },
            providers = settings.providers,
        )
    }
}

@Composable
private fun KnowledgeBaseCard(
    kb: KnowledgeBase,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    ingesting: Boolean,
    progress: Float,
    onEmbeddingModelChange: (Uuid) -> Unit,
    onAddDocument: (android.net.Uri) -> Unit,
    onDeleteDocument: (Uuid) -> Unit,
    onDelete: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onAddDocument(uri) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HugeIcons.Database, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    kb.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDelete) {
                    Icon(HugeIcons.Delete01, contentDescription = "Delete knowledge base")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Embedding model:", style = MaterialTheme.typography.bodyMedium)
                ModelSelector(
                    modelId = kb.embeddingModelId,
                    providers = providers,
                    type = ModelType.EMBEDDING,
                    onSelect = { onEmbeddingModelChange(it.id) },
                )
            }

            Text(
                "Documents (${kb.documents.size})",
                style = MaterialTheme.typography.labelLarge,
            )
            kb.documents.forEach { doc ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HugeIcons.File01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        "${doc.fileName} (${doc.chunkCount})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { onDeleteDocument(doc.id) }) {
                        Icon(HugeIcons.Delete01, contentDescription = "Delete document", modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (ingesting) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = kb.embeddingModelId != null,
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add document", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun CreateKnowledgeBaseDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, embeddingModelId: Uuid?) -> Unit,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
) {
    var name by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf<Uuid?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Knowledge Base") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Embedding model:", style = MaterialTheme.typography.bodyMedium)
                    ModelSelector(
                        modelId = modelId,
                        providers = providers,
                        type = ModelType.EMBEDDING,
                        onSelect = { modelId = it.id },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, modelId) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
