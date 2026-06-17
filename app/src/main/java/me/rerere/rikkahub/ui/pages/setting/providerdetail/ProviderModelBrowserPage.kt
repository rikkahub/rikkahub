package me.rerere.rikkahub.ui.pages.setting.providerdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.ui.components.ai.ModelAbilityTag
import me.rerere.rikkahub.ui.components.ai.ModelModalityTag
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import org.koin.androidx.compose.koinViewModel

/**
 * Full-screen model browser for one provider: search the fetched catalog, toggle individual models
 * on/off, bulk-enable a filtered subset (guarded — see [canBulkEnable]), and add a model manually.
 * Replaces the cramped bottom-sheet picker so large catalogs (hundreds of models) are browsable.
 *
 * Connection failures from the fetch are surfaced here (the place the user looks for models) with the
 * classified reason, instead of an empty list with no explanation.
 */
@Composable
fun ProviderModelBrowserPage(
    providerId: Uuid,
    settingVM: SettingVM = koinViewModel(),
    detailVM: ProviderDetailViewModel = koinViewModel(),
) {
    val settings by settingVM.settings.collectAsStateWithLifecycle()
    val provider = settings.providers.find { it.id == providerId } ?: return
    val catalogState by detailVM.catalog.collectAsStateWithLifecycle()

    // Re-fetch when the provider CONFIG changes (key / baseUrl / endpoint), not just on id. The
    // toolbar persists the unsaved Config draft right before navigating here, and that write lands
    // asynchronously — keying on a models-stripped config fingerprint makes the freshly-saved config
    // trigger a refetch instead of leaving the first (stale) fetch standing. Model toggles don't
    // change this key, so they don't cause redundant refetches.
    LaunchedEffect(provider.copyProvider(models = emptyList())) { detailVM.refreshCatalog(provider) }

    var query by rememberSaveable { mutableStateOf("") }
    val catalog = (catalogState as? ModelCatalogState.Loaded)?.models.orEmpty()
    val enabledIds = remember(provider.models) { provider.models.map { it.modelId }.toSet() }
    val filtered = remember(catalog, query) { filterModels(catalog, query).sortedBy { it.modelId } }

    // Atomic per-item mutation: [transform] runs against the CURRENT persisted provider (looked up
    // by id inside settingsStore.update), not the captured snapshot, so fast consecutive toggles
    // can't clobber each other.
    fun mutateProvider(transform: (ProviderSetting) -> ProviderSetting) {
        settingVM.updateSettings { current ->
            current.copy(providers = current.providers.map { if (it.id == providerId) transform(it) else it })
        }
    }

    val manualAdd = useEditState<Model> { model -> mutateProvider { it.addModel(model) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { detailVM.refreshCatalog(provider) }) {
                        Icon(HugeIcons.Refresh01, contentDescription = "Refresh models")
                    }
                },
            )
        },
        floatingActionButton = {
            // Labelled FAB: the per-row "+" enables a catalog model, so a bare "+" here is
            // ambiguous. The text makes clear this adds a model by typing its id manually.
            ExtendedFloatingActionButton(
                onClick = { manualAdd.open(Model()) },
                icon = { Icon(HugeIcons.Add01, contentDescription = null) },
                text = { Text("Add manually") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search models") },
                placeholder = { Text("e.g. gpt-4o, claude, embedding") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            CatalogStatus(
                state = catalogState,
                onRetry = { detailVM.refreshCatalog(provider) },
            )

            val unselectedCount = filtered.count { it.modelId !in enabledIds }
            when {
                canBulkEnable(query, filtered, enabledIds) -> {
                    TextButton(
                        onClick = {
                            mutateProvider { p ->
                                val already = p.models.map { it.modelId }.toSet()
                                val toAdd = filtered
                                    .filter { it.modelId !in already }
                                    .map { it.withRegistryMeta() }
                                p.copyProvider(models = p.models + toAdd)
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Enable all $unselectedCount") }
                }

                canBulkDisable(query, filtered, enabledIds) -> {
                    TextButton(
                        onClick = {
                            mutateProvider { p ->
                                p.copyProvider(
                                    models = p.models.filter { enabled ->
                                        filtered.none { it.modelId == enabled.modelId }
                                    }
                                )
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Disable all") }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(filtered, key = { it.modelId }) { model ->
                    val enabled = model.modelId in enabledIds
                    ModelBrowserCard(
                        model = model,
                        enabled = enabled,
                        onToggle = {
                            // Decide enable-vs-disable from the CURRENT persisted models inside the
                            // atomic update, not the captured `enabled` flag, so a stale snapshot
                            // can't double-add or no-op.
                            mutateProvider { p ->
                                val existing = p.models.firstOrNull { it.modelId == model.modelId }
                                if (existing != null) p.delModel(existing)
                                else p.addModel(model.withRegistryMeta())
                            }
                        },
                    )
                }
            }
        }
    }

    if (manualAdd.isEditing) {
        manualAdd.currentState?.let { modelState ->
            val sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            )
            val scope = rememberCoroutineScope()
            ModalBottomSheet(
                onDismissRequest = { manualAdd.dismiss() },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = {
                    IconButton(onClick = {
                        scope.launch {
                            sheetState.hide()
                            manualAdd.dismiss()
                        }
                    }) {
                        Icon(HugeIcons.ArrowDown01, null)
                    }
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "Add model", style = MaterialTheme.typography.titleLarge)
                    // ModelSettingsForm hosts its own HorizontalPager (each tab a verticalScroll),
                    // which must be given a BOUNDED height. It therefore sits in a weight(1f) box,
                    // NOT inside a verticalScroll sheet (FormBottomSheet) — that would hand the pager
                    // an infinite max height and crash its scroll container.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ModelSettingsForm(
                            model = modelState,
                            onModelChange = { manualAdd.currentState = it },
                            isEdit = false,
                            parentProvider = provider,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = { manualAdd.dismiss() }) { Text("Cancel") }
                        TextButton(
                            onClick = {
                                if (modelState.modelId.isNotBlank() && modelState.displayName.isNotBlank()) {
                                    manualAdd.confirm()
                                }
                            },
                        ) { Text("Add") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogStatus(
    state: ModelCatalogState,
    onRetry: () -> Unit,
) {
    when (state) {
        is ModelCatalogState.Loading -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("Fetching models…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        is ModelCatalogState.Failed -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        HugeIcons.Connect,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = connectionResultTitle(state.result),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = connectionResultHint(state.result),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }

        ModelCatalogState.Idle, is ModelCatalogState.Loaded -> Unit
    }
}

@Composable
private fun ModelBrowserCard(
    model: Model,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val modelMeta = remember(model) {
        model.copy(
            inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId),
            outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId),
            abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId),
        )
    }
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AutoAIIcon(model.modelId, Modifier.size(32.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = model.modelId,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ModelModalityTag(model = modelMeta)
                    ModelAbilityTag(model = modelMeta)
                }
            }
            IconButton(onClick = onToggle) {
                if (enabled) Icon(HugeIcons.Cancel01, contentDescription = "Disable")
                else Icon(HugeIcons.Add01, contentDescription = "Enable")
            }
        }
    }
}

/** Enrich a discovered model with registry-known modalities/abilities before it is enabled. */
private fun Model.withRegistryMeta(): Model = copy(
    inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
    outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
    abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
)
