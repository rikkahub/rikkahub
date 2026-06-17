package me.rerere.rikkahub.ui.pages.setting.providerdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ConnectionResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProbeOutcome
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.classifyProviderConnection

/**
 * The fetched model catalog used by the model browser. Replaces the old `produceState(emptyList())`
 * where a failed fetch and an empty success were observationally identical: [Failed] now carries the
 * classified reason so the UI can tell the user WHY no models appeared (bad key / no /models / wrong
 * endpoint) and offer the right next step.
 */
sealed interface ModelCatalogState {
    data object Idle : ModelCatalogState
    data object Loading : ModelCatalogState
    data class Loaded(val models: List<Model>) : ModelCatalogState
    data class Failed(val result: ConnectionResult) : ModelCatalogState
}

/**
 * Owns the model-catalog fetch for one provider-detail screen. The provider setting is passed
 * per-action (not held) so the screen's live config draft always drives the probe.
 */
class ProviderDetailViewModel(
    private val providerManager: ProviderManager,
) : ViewModel() {

    private val _catalog = MutableStateFlow<ModelCatalogState>(ModelCatalogState.Idle)
    val catalog = _catalog.asStateFlow()

    private var catalogJob: Job? = null

    /** Fetch the model catalog, surfacing failure as a classified [ModelCatalogState.Failed]. */
    fun refreshCatalog(setting: ProviderSetting) {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            _catalog.value = ModelCatalogState.Loading
            val (result, models) = probe(setting)
            _catalog.value =
                if (models.isNotEmpty()) ModelCatalogState.Loaded(models)
                else ModelCatalogState.Failed(result)
        }
    }

    /**
     * One probe round: fetch the model list, then spend a chat probe ONLY when the list didn't
     * already prove the connection (to disambiguate a /models failure) AND a real model id exists to
     * probe with — never invent one (maintainer decision). Returns the verdict plus the fetched
     * catalog (empty unless the list call succeeded).
     */
    private suspend fun probe(setting: ProviderSetting): Pair<ConnectionResult, List<Model>> {
        return try {
            val provider = providerManager.getProviderByType(setting)
            val modelsProbe = provider.probeModelList(setting)

            // The list already proves the connection when it returned models OR a 429 (authed +
            // reachable, just throttled — classifyProviderConnection makes 429 a terminal Valid). In
            // both cases skip the chat probe: it would be redundant (models case) or also throttled
            // and ignored (429 case).
            val http = modelsProbe.outcome as? ProbeOutcome.Http
            val listProvedConnection = http != null && (
                http.status == 429 ||
                    (http.body is ProbeOutcome.Body.ModelList && (http.body as ProbeOutcome.Body.ModelList).count > 0)
                )

            val chatProbe = if (!listProvedConnection) {
                val modelId = setting.models.firstOrNull { it.type == ModelType.CHAT }?.modelId
                    ?: setting.models.firstOrNull()?.modelId
                modelId?.let { provider.probeChat(setting, it) }
            } else {
                null
            }

            val result = classifyProviderConnection(modelsProbe.outcome, chatProbe)
            result to modelsProbe.models
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A throwable here is NOT a network response the probe helpers classified — it escaped
            // BEFORE the request was sent: a malformed Base URL (toHttpUrl), a Vertex service-account
            // token failure, an empty key. Without this, refreshCatalog would set Loading and never
            // resolve. Treat it as a wrong-endpoint verdict so the UI shows WHY.
            ConnectionResult.UnreachableOrWrongEndpoint to emptyList()
        }
    }
}
