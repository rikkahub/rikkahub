package me.rerere.rikkahub.ui.pages.mediacreation

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationCapabilities
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.MediaCreationFiles
import me.rerere.rikkahub.data.files.RemoteFileStore
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationAssetType
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationNode
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationSession
import me.rerere.rikkahub.data.model.canSubmit
import me.rerere.rikkahub.data.model.supportedBy
import me.rerere.rikkahub.data.model.withRequired
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.service.MediaCreationSubmission
import me.rerere.ui.sketch.SketchResult
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private const val TAG = "MediaCreationVM"

/** 草稿选中的模型，以及它所属的提供商和适配器能力。 */
data class MediaCreationModelSelection(
    val provider: MediaGenerationProviderSetting,
    val model: MediaGenerationModel,
    val capabilities: MediaGenerationCapabilities,
)

/** 可以再次放进输入区的一项历史产出。 */
data class MediaCreationRecentOutput(
    val recordId: Uuid,
    val output: MediaCreationOutput,
)

/** 一次成功的提交：新记录落在时间线的第 [position] 项上。 */
data class MediaCreationSubmitted(
    val nodeId: Uuid,
    val position: Int,
)

sealed interface MediaCreationEvent {
    data class Error(val message: String) : MediaCreationEvent

    /** 当前模型的素材需要公网地址，但还没有配置上传用的 S3。 */
    data object UploadNotConfigured : MediaCreationEvent

    /** 会话已经不存在，页面应当关闭。 */
    data object SessionGone : MediaCreationEvent
}

/** 一个媒体创作会话：它的时间线和输入区的草稿。 */
@OptIn(FlowPreview::class)
class MediaCreationVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val repository: MediaCreationRepository,
    private val service: MediaCreationService,
    private val remoteFileStore: RemoteFileStore,
    private val appScope: AppScope,
) : ViewModel() {
    val sessionId: Uuid = Uuid.parse(id)

    // 输入区的草稿；null 表示还在加载
    private val draftState = MutableStateFlow<MediaCreationDraft?>(null)

    // 已经写进数据库的草稿，只在主线程读写
    private var savedDraft: MediaCreationDraft? = null

    /** 提示词输入框。草稿里的 prompt 跟随它更新，需要准确值的地方先调用 [syncPrompt]。 */
    val promptState = TextFieldState()

    val settings = settingsStore.settingsFlow

    val draft: StateFlow<MediaCreationDraft> = draftState
        .map { it ?: MediaCreationDraft() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MediaCreationDraft())

    val session: StateFlow<MediaCreationSession?> = repository.observeSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nodes: Flow<PagingData<MediaCreationNode>> = repository.observeNodes(sessionId).cachedIn(viewModelScope)

    val selection: StateFlow<MediaCreationModelSelection?> =
        combine(settingsStore.settingsFlow, draft) { settings, draft ->
            settings.mediaGenerationProviders.findSelection(draft.modelId)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uploadConfigured: StateFlow<Boolean> = settingsStore.settingsFlow
        .map { remoteFileStore.isConfigured }
        .stateIn(viewModelScope, SharingStarted.Eagerly, remoteFileStore.isConfigured)

    val recentOutputs: StateFlow<List<MediaCreationRecentOutput>> =
        repository.observeRecentSucceededRecords(RECENT_RECORD_LIMIT)
            .map { records ->
                records.flatMap { record -> record.outputs.map { MediaCreationRecentOutput(record.id, it) } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = Channel<MediaCreationEvent>(Channel.BUFFERED)
    val events: Flow<MediaCreationEvent> = _events.receiveAsFlow()

    private val _submitted = MutableSharedFlow<MediaCreationSubmitted>(extraBufferCapacity = 1)

    /** 每提交一条新记录发出一次，时间线据此滚到它所在的那一项。 */
    val submitted: Flow<MediaCreationSubmitted> = _submitted.asSharedFlow()

    // 输入区最近一次提交，还没有结果时不接受下一次
    private var generateJob: Job? = null

    // 同一个文件连续选用两次时，第二次要等第一次的副本写完
    private val draftCopyMutex = Mutex()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            // 通知对应的会话可能已经删掉了，打开期间也可能在别处被删掉
            repository.observeSession(sessionId).first { it == null }
            _events.send(MediaCreationEvent.SessionGone)
        }
        viewModelScope.launch {
            snapshotFlow { promptState.text.toString() }.collect { syncPrompt() }
        }
        viewModelScope.launch {
            draftState.filterNotNull().debounce(DRAFT_SAVE_DELAY).collect { save(it) }
        }
    }

    override fun onCleared() {
        syncPrompt()
        val draft = draftState.value
        if (draft != null && draft != savedDraft) {
            appScope.launch { repository.saveDraft(sessionId, draft) }
        }
    }

    fun resolve(path: String): File = repository.resolve(path)

    private suspend fun load() {
        val session = repository.getSession(sessionId) ?: return
        val providers = settingsStore.settingsFlow.first { !it.init }.mediaGenerationProviders
        val assets = withContext(Dispatchers.IO) {
            val assets = session.draft.assets.filter { repository.resolve(it.path).isFile }
            // 草稿目录里不再被引用的文件是之前移除的素材
            val referenced = assets.map { repository.resolve(it.path) }.toSet()
            repository.draftDir(session.id).listFiles()?.filterNot { it in referenced }?.forEach { it.delete() }
            assets
        }
        val modelId = session.draft.modelId?.takeIf { providers.findSelection(it) != null }
            ?: providers.firstNotNullOfOrNull { provider ->
                provider.models.firstOrNull { provider.capabilities(it.kind) != null }
            }?.id
        val editingNodeId = session.draft.editingNodeId?.takeIf { repository.hasNode(it) }
        savedDraft = session.draft
        draftState.value = session.draft.copy(modelId = modelId, assets = assets, editingNodeId = editingNodeId)
        promptState.setTextAndPlaceCursorAtEnd(session.draft.prompt)
    }

    private suspend fun save(draft: MediaCreationDraft) {
        if (draft == savedDraft) return
        repository.saveDraft(sessionId, draft)
        savedDraft = draft
    }

    // ---- 草稿 ----

    private fun syncPrompt() = updateDraft { it.copy(prompt = promptState.text.toString()) }

    fun updateParams(params: MediaCreationParams) = updateDraft { it.copy(params = params) }

    /** 清空提示词和素材，模型和参数保留。 */
    fun clearDraft() {
        promptState.clearText()
        updateDraft { it.copy(prompt = "", assets = emptyList(), editingNodeId = null) }
    }

    /** 不再修改原来那一项：输入区的内容保留，生成时另起一项。 */
    fun stopEditing() = updateDraft { it.copy(editingNodeId = null) }

    /**
     * 切换模型。换了厂商或类型时参数的取值不再通用，恢复成默认；素材调整成新模型能接受的形态。
     */
    fun selectModel(provider: MediaGenerationProviderSetting, model: MediaGenerationModel) = updateDraft { draft ->
        val capabilities = provider.capabilities(model.kind) ?: return@updateDraft draft
        val previous = currentSelection(draft)
        val sameShape = previous != null &&
            previous.provider::class == provider::class &&
            previous.model.kind == model.kind
        draft.copy(
            modelId = model.id,
            params = if (sameShape) draft.params else MediaCreationParams(),
            assets = draft.assets.supportedBy(capabilities),
        )
    }

    /** 把相册里选的文件导入草稿。 */
    fun importAssets(uris: List<Uri>, type: MediaCreationAssetType, role: ImageRole) {
        if (draftState.value == null || uris.isEmpty()) return
        viewModelScope.launch {
            val dir = repository.draftDir(sessionId)
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        when (type) {
                            MediaCreationAssetType.IMAGE -> MediaCreationFiles.importImage(context, uri, dir)
                            MediaCreationAssetType.VIDEO -> MediaCreationFiles.importVideo(context, uri, dir)
                        }
                    }.onFailure {
                        Log.e(TAG, "Failed to import $uri", it)
                    }.getOrNull()
                }
            }
            if (files.size < uris.size) {
                _events.send(
                    MediaCreationEvent.Error(
                        context.getString(R.string.media_creation_page_files_unreadable, uris.size - files.size)
                    )
                )
            }
            files.forEach { addAsset(MediaCreationAsset(repository.relativePath(it), type, role)) }
        }
    }

    /**
     * 把画板上画的图放进草稿。[replacing] 是画的时候垫在下面的那项素材，画好的图换掉它。
     */
    fun addSketch(result: SketchResult, role: ImageRole, replacing: MediaCreationAsset? = null) {
        if (draftState.value == null) return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = repository.draftDir(sessionId).apply { mkdirs() }
                    File(dir, "${Uuid.random()}.${result.extension}").also { it.writeBytes(result.encode()) }
                }.onFailure {
                    Log.e(TAG, "Failed to save sketch", it)
                }.getOrNull()
            }
            if (file == null) {
                _events.send(MediaCreationEvent.Error(context.getString(R.string.media_creation_page_asset_add_failed)))
                return@launch
            }
            val asset = MediaCreationAsset(repository.relativePath(file), MediaCreationAssetType.IMAGE, role)
            updateDraft { draft ->
                // 原来那项在保存的这会儿被移走了的话，画好的图照常加进来
                val assets = if (replacing != null && replacing in draft.assets) {
                    draft.assets.map { if (it == replacing) asset else it }
                } else {
                    draft.assets + asset
                }
                draft.withAssets(assets)
            }
        }
    }

    fun removeAsset(asset: MediaCreationAsset) = updateDraft { it.copy(assets = it.assets - asset) }

    fun setAssetRole(asset: MediaCreationAsset, role: ImageRole) = updateDraft { draft ->
        // 挪到末尾：同一个角色只留最后放进去的那张
        draft.withAssets(draft.assets - asset + asset.copy(role = role))
    }

    /**
     * 把一项产出作为图片素材放进输入区。当前模型不接受 [role] 时切换到能接受的模型（首帧、尾帧需要视频模型）。
     */
    fun useImage(output: MediaCreationOutput, role: ImageRole) {
        viewModelScope.launch {
            if (!ensureModel { role in it.imageRoles }) return@launch
            addCopy(output.path, MediaCreationAssetType.IMAGE, role)
        }
    }

    /** 把一段视频作为参考视频放进输入区。 */
    fun useVideo(output: MediaCreationOutput) {
        viewModelScope.launch {
            if (!ensureModel { it.videoInput }) return@launch
            addCopy(output.path, MediaCreationAssetType.VIDEO)
        }
    }

    /** 续写：用视频的最后一帧作为下一段的首帧。 */
    fun continueVideo(output: MediaCreationOutput) {
        viewModelScope.launch {
            // 接口随视频返回了尾帧就用它，没有的话自己从视频里抽
            val framePath = output.lastFramePath?.let { copyToDraft(it) } ?: withContext(Dispatchers.IO) {
                val target = File(repository.draftDir(sessionId), "${Uuid.random()}.jpg")
                val extracted = MediaCreationFiles.extractFrame(repository.resolve(output.path), target, last = true)
                if (extracted) repository.relativePath(target) else null
            }
            if (framePath == null) {
                _events.send(
                    MediaCreationEvent.Error(context.getString(R.string.media_creation_page_last_frame_failed))
                )
                return@launch
            }
            if (!ensureModel { ImageRole.FIRST_FRAME in it.imageRoles }) return@launch
            addAsset(MediaCreationAsset(framePath, MediaCreationAssetType.IMAGE, ImageRole.FIRST_FRAME))
        }
    }

    private suspend fun addCopy(path: String, type: MediaCreationAssetType, role: ImageRole = ImageRole.REFERENCE) {
        val copy = copyToDraft(path)
        if (copy == null) {
            _events.send(MediaCreationEvent.Error(context.getString(R.string.media_creation_page_asset_add_failed)))
            return
        }
        addAsset(MediaCreationAsset(copy, type, role))
    }

    /**
     * 把一条记录的产出复制进草稿目录，返回副本的路径：产出可能来自别的会话，那条记录或会话被删除时草稿不受影响。
     * 副本的名字由来源决定，同一个文件再选一次得到的还是同一份。复制不了时返回 null。
     */
    private suspend fun copyToDraft(path: String): String? = draftCopyMutex.withLock {
        withContext(Dispatchers.IO) {
            val source = repository.resolve(path)
            val target = File(repository.draftDir(sessionId), "${source.parentFile?.name}_${source.name}")
            runCatching {
                if (!target.isFile) source.copyTo(target)
                repository.relativePath(target)
            }.onFailure {
                Log.e(TAG, "Failed to copy $path into the draft", it)
                target.delete()
            }.getOrNull()
        }
    }

    private fun addAsset(asset: MediaCreationAsset) = updateDraft { it.withAssets(it.assets + asset) }

    private fun MediaCreationDraft.withAssets(assets: List<MediaCreationAsset>): MediaCreationDraft {
        val capabilities = currentSelection(this)?.capabilities
        val supported = if (capabilities != null) assets.distinct().supportedBy(capabilities) else assets.distinct()
        return copy(assets = supported.takeLast(MAX_ASSETS))
    }

    private fun updateDraft(transform: (MediaCreationDraft) -> MediaCreationDraft) {
        val draft = draftState.value ?: return
        draftState.value = transform(draft)
    }

    private fun currentSelection(draft: MediaCreationDraft? = draftState.value): MediaCreationModelSelection? =
        settingsStore.settingsFlow.value.mediaGenerationProviders.findSelection(draft?.modelId)

    // 当前模型不满足要求时换一个满足的；这里的要求（首尾帧、视频素材）都只有视频模型能满足
    private suspend fun ensureModel(requirement: (MediaGenerationCapabilities) -> Boolean): Boolean {
        if (currentSelection()?.capabilities?.let(requirement) == true) return true
        val candidates = settingsStore.settingsFlow.value.mediaGenerationProviders.flatMap { provider ->
            provider.models.mapNotNull { model ->
                provider.capabilities(model.kind)?.takeIf(requirement)
                    ?.let { MediaCreationModelSelection(provider, model, it) }
            }
        }
        if (candidates.isEmpty()) {
            _events.send(MediaCreationEvent.Error(context.getString(R.string.media_creation_page_no_video_model)))
            return false
        }
        // 优先用上一次生成视频时的模型
        val recent = repository.getLatestRecordOfKind(MediaKind.VIDEO)
        val target = candidates.find { it.provider.id == recent?.providerId && it.model.modelId == recent.modelId }
            ?: candidates.first()
        selectModel(target.provider, target.model)
        return true
    }

    // ---- 生成 ----

    /** 提交输入区的内容。提交成功后清空提示词和素材，模型和参数保留。 */
    fun generate() {
        if (generateJob?.isActive == true) return
        syncPrompt()
        val submitted = draftState.value ?: return
        val selection = currentSelection(submitted)
        if (selection == null) {
            _events.trySend(
                MediaCreationEvent.Error(context.getString(R.string.media_creation_page_select_model_first))
            )
            return
        }
        if (!submitted.canSubmit(selection.capabilities)) return
        generateJob = submit(
            MediaCreationSubmission(
                sessionId = sessionId,
                nodeId = submitted.editingNodeId,
                provider = selection.provider,
                model = selection.model,
                prompt = submitted.prompt,
                params = submitted.params,
                assets = submitted.assets,
            ),
            selection.capabilities,
        ) {
            val untouched = promptState.text.toString() == submitted.prompt &&
                draftState.value?.assets == submitted.assets
            if (untouched) {
                clearDraft()
            } else {
                // 提交期间又动过输入区：新的内容留着，但不再算作对原来那一项的修改
                updateDraft {
                    if (it.editingNodeId == submitted.editingNodeId) it.copy(editingNodeId = null) else it
                }
            }
        }
    }

    /** 用同样的输入和参数再生成一次，作为同一项的新版本。 */
    fun rerun(record: MediaCreationRecord) {
        val provider = settingsStore.settingsFlow.value.mediaGenerationProviders.find { it.id == record.providerId }
        val capabilities = provider?.capabilities(record.kind)
        if (provider == null || capabilities == null) {
            _events.trySend(
                MediaCreationEvent.Error(
                    context.getString(R.string.media_creation_error_provider_deleted, record.providerName)
                )
            )
            return
        }
        submit(
            MediaCreationSubmission(
                sessionId = record.sessionId,
                nodeId = record.nodeId,
                provider = provider,
                model = MediaGenerationModel(modelId = record.modelId, kind = record.kind),
                prompt = record.prompt,
                params = record.params,
                assets = record.inputs,
            ),
            capabilities,
        )
    }

    private fun submit(
        submission: MediaCreationSubmission,
        capabilities: MediaGenerationCapabilities,
        onSubmitted: () -> Unit = {},
    ): Job? {
        if (capabilities.requiresRemoteInputs && submission.assets.isNotEmpty() && !remoteFileStore.isConfigured) {
            _events.trySend(MediaCreationEvent.UploadNotConfigured)
            return null
        }
        // 接口必填的参数没有设置时用默认值，记录里保存的就是实际下发的值
        val defaults = submission.provider.presets(submission.model.kind).defaults
        val completed = submission.copy(params = submission.params.withRequired(capabilities, defaults))
        return viewModelScope.launch {
            try {
                val record = service.submit(completed)
                _submitted.tryEmit(MediaCreationSubmitted(record.nodeId, repository.getNodePosition(record.nodeId)))
                onSubmitted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit", e)
                _events.send(
                    MediaCreationEvent.Error(e.message ?: context.getString(R.string.media_creation_page_submit_failed))
                )
            }
        }
    }

    /** 把一条记录的提示词、素材和参数填回输入区，之后生成的结果成为它所在那一项的新版本。 */
    fun editFrom(record: MediaCreationRecord) {
        updateDraft { draft ->
            val providers = settingsStore.settingsFlow.value.mediaGenerationProviders
            val model = providers.find { it.id == record.providerId }
                ?.models?.find { it.modelId == record.modelId && it.kind == record.kind }
            // 模型还在就切回去；已经被删掉的话保留当前模型和参数，只带回提示词和素材
            draft.copy(
                modelId = model?.id ?: draft.modelId,
                prompt = record.prompt,
                params = if (model != null) record.params else draft.params,
                editingNodeId = record.nodeId,
            ).withAssets(record.inputs)
        }
        promptState.setTextAndPlaceCursorAtEnd(record.prompt)
    }

    fun retry(record: MediaCreationRecord) {
        viewModelScope.launch { service.retry(record.id) }
    }

    fun cancel(record: MediaCreationRecord) {
        viewModelScope.launch { service.cancel(record.id) }
    }

    /** 让时间线上的一项显示上一个（[offset] 为 -1）或下一个（1）版本。 */
    fun switchVersion(node: MediaCreationNode, offset: Int) {
        viewModelScope.launch { repository.switchVersion(node.id, offset) }
    }

    /** 删除一个版本；它是所在项唯一的版本时，这一项也从时间线上消失。 */
    fun delete(record: MediaCreationRecord) {
        viewModelScope.launch {
            service.deleteRecord(record.id)
            val nodeRemoved = !repository.hasNode(record.nodeId)
            // 草稿可能正引用着这条记录的文件，也可能正在修改刚刚消失的那一项
            val recordDir = repository.relativePath(repository.recordDir(record.sessionId, record.id)) + File.separator
            updateDraft { draft ->
                draft.copy(
                    assets = draft.assets.filterNot { it.path.startsWith(recordDir) },
                    editingNodeId = draft.editingNodeId.takeUnless { nodeRemoved && it == record.nodeId },
                )
            }
        }
    }

    companion object {
        private const val MAX_ASSETS = 16
        private const val RECENT_RECORD_LIMIT = 60
        private val DRAFT_SAVE_DELAY = 500.milliseconds
    }
}

private fun List<MediaGenerationProviderSetting>.findSelection(modelId: Uuid?): MediaCreationModelSelection? {
    if (modelId == null) return null
    forEach { provider ->
        val model = provider.models.find { it.id == modelId } ?: return@forEach
        val capabilities = provider.capabilities(model.kind) ?: return@forEach
        return MediaCreationModelSelection(provider, model, capabilities)
    }
    return null
}
