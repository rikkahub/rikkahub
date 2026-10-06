package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationCapabilities
import me.rerere.mediagen.provider.MediaGenerationParameter
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 媒体创作的一个会话：一条生成记录的时间线，加上输入区的草稿。
 */
data class MediaCreationSession(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val draft: MediaCreationDraft = MediaCreationDraft(),
    val createAt: Instant = Instant.now(),
    val updateAt: Instant = Instant.now(),
    /** 时间线上的项数，同一项的多个版本只算一次。 */
    val nodeCount: Int = 0,
    val activeCount: Int = 0,
    /** 时间线上最新一项成功的生成的第一个产出，会话列表拿它当封面；还没有产出时为 null。 */
    val cover: MediaCreationOutput? = null,
)

/**
 * 输入区的内容。[modelId] 是 MediaGenerationModel.id，模型被删除后草稿里的选择自然失效。
 *
 * [editingNodeId] 非空表示输入区的内容是从时间线上那一项填回来修改的，生成的结果会成为它的新版本。
 */
@Serializable
data class MediaCreationDraft(
    val modelId: Uuid? = null,
    val prompt: String = "",
    val assets: List<MediaCreationAsset> = emptyList(),
    val params: MediaCreationParams = MediaCreationParams(),
    val editingNodeId: Uuid? = null,
)

@Serializable
enum class MediaCreationAssetType {
    IMAGE,
    VIDEO,
}

/**
 * 一份输入素材。[path] 相对于应用的 filesDir；[role] 只对图片有意义。
 */
@Serializable
data class MediaCreationAsset(
    val path: String,
    val type: MediaCreationAssetType = MediaCreationAssetType.IMAGE,
    val role: ImageRole = ImageRole.REFERENCE,
)

/**
 * 用户选择的公共参数，null 表示交给接口的默认值。
 */
@Serializable
data class MediaCreationParams(
    val count: Int? = null,
    val resolution: String? = null,
    val aspectRatio: String? = null,
    val durationSeconds: Int? = null,
    val generateAudio: Boolean? = null,
    val watermark: Boolean? = null,
    val seed: Long? = null,
    val promptEnhancement: Boolean? = null,
)

/**
 * 一项已经落盘的产出，路径都相对于应用的 filesDir。
 */
@Serializable
data class MediaCreationOutput(
    val path: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Double? = null,
    /** 视频的封面图。 */
    val posterPath: String? = null,
    /** 接口随视频返回的尾帧图。 */
    val lastFramePath: String? = null,
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")
}

enum class MediaCreationStatus {
    /** 正在上传素材，还没有提交给接口。 */
    PREPARING,
    QUEUED,
    RUNNING,

    /** 接口已经给出结果，正在下载到本地。 */
    DOWNLOADING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    val isActive: Boolean
        get() = this in ACTIVE

    companion object {
        val ACTIVE = setOf(PREPARING, QUEUED, RUNNING, DOWNLOADING)
    }
}

/**
 * 一次生成。
 *
 * [nodeId] 是它在时间线上所属的那一项：再来一次、修改后重新生成的记录沿用原来的 [nodeId]，成为同一项的新版本。
 *
 * [taskId] 只在服务端还有任务可以查询时非空：同步接口没有任务，任务在服务端失败后也会清空。
 * 因此被中断的记录（应用被杀、取消等待、轮询或下载出错）只要还带着 [taskId]，就可以接着查询而不必重新提交。
 */
data class MediaCreationRecord(
    val id: Uuid = Uuid.random(),
    val sessionId: Uuid,
    val nodeId: Uuid = Uuid.random(),
    val providerId: Uuid,
    val providerName: String,
    /** 下发给接口的模型名。 */
    val modelId: String,
    val kind: MediaKind,
    val prompt: String,
    val params: MediaCreationParams = MediaCreationParams(),
    val inputs: List<MediaCreationAsset> = emptyList(),
    val status: MediaCreationStatus = MediaCreationStatus.PREPARING,
    val taskId: String? = null,
    val error: String? = null,
    val outputs: List<MediaCreationOutput> = emptyList(),
    val createAt: Instant = Instant.now(),
    val updateAt: Instant = Instant.now(),
)

/**
 * 时间线上的一项：同一个位置上的若干个版本，界面只显示选中的那一个 [record]。
 */
data class MediaCreationNode(
    val record: MediaCreationRecord,
    /** [record] 是第几个版本，从 0 开始。 */
    val versionIndex: Int = 0,
    val versionCount: Int = 1,
) {
    val id: Uuid
        get() = record.nodeId
}

/**
 * 只保留 [capabilities] 声明支持的参数：切换模型后，上一个模型留下的选项不应该被带进请求。
 */
fun MediaCreationParams.supportedBy(capabilities: MediaGenerationCapabilities): MediaCreationParams {
    fun <T> T?.takeIfSupported(parameter: MediaGenerationParameter): T? =
        takeIf { parameter in capabilities.parameters }
    return MediaCreationParams(
        count = count.takeIfSupported(MediaGenerationParameter.COUNT),
        resolution = resolution?.takeIf(String::isNotBlank).takeIfSupported(MediaGenerationParameter.RESOLUTION),
        aspectRatio = aspectRatio?.takeIf(String::isNotBlank).takeIfSupported(MediaGenerationParameter.ASPECT_RATIO),
        durationSeconds = durationSeconds.takeIfSupported(MediaGenerationParameter.DURATION),
        generateAudio = generateAudio.takeIfSupported(MediaGenerationParameter.GENERATE_AUDIO),
        watermark = watermark.takeIfSupported(MediaGenerationParameter.WATERMARK),
        seed = seed.takeIfSupported(MediaGenerationParameter.SEED),
        promptEnhancement = promptEnhancement.takeIfSupported(MediaGenerationParameter.PROMPT_ENHANCEMENT),
    )
}

/**
 * 接口必填、但还没有设置的参数用 [defaults] 里的值补上，其余保持原样。
 */
fun MediaCreationParams.withRequired(
    capabilities: MediaGenerationCapabilities,
    defaults: MediaCreationParams,
): MediaCreationParams {
    fun <T> T?.orRequired(parameter: MediaGenerationParameter, default: T?): T? =
        this ?: default.takeIf { parameter in capabilities.requiredParameters }
    return MediaCreationParams(
        count = count.orRequired(MediaGenerationParameter.COUNT, defaults.count),
        resolution = resolution?.takeIf(String::isNotBlank)
            .orRequired(MediaGenerationParameter.RESOLUTION, defaults.resolution),
        aspectRatio = aspectRatio?.takeIf(String::isNotBlank)
            .orRequired(MediaGenerationParameter.ASPECT_RATIO, defaults.aspectRatio),
        durationSeconds = durationSeconds.orRequired(MediaGenerationParameter.DURATION, defaults.durationSeconds),
        generateAudio = generateAudio.orRequired(MediaGenerationParameter.GENERATE_AUDIO, defaults.generateAudio),
        watermark = watermark.orRequired(MediaGenerationParameter.WATERMARK, defaults.watermark),
        seed = seed.orRequired(MediaGenerationParameter.SEED, defaults.seed),
        promptEnhancement = promptEnhancement
            .orRequired(MediaGenerationParameter.PROMPT_ENHANCEMENT, defaults.promptEnhancement),
    )
}

/**
 * 把素材调整成 [capabilities] 能接受的形态：不支持的图片角色退回参考图，不接受的素材类型直接丢弃。
 * 首帧和尾帧各自只保留最后放进去的一张。
 */
fun List<MediaCreationAsset>.supportedBy(capabilities: MediaGenerationCapabilities): List<MediaCreationAsset> {
    val coerced = mapNotNull { asset ->
        when (asset.type) {
            MediaCreationAssetType.IMAGE -> when {
                capabilities.imageRoles.isEmpty() -> null
                asset.role in capabilities.imageRoles -> asset
                else -> asset.copy(role = ImageRole.REFERENCE)
            }

            MediaCreationAssetType.VIDEO -> asset.takeIf { capabilities.videoInput }
        }
    }
    return coerced.filterIndexed { index, asset ->
        asset.type != MediaCreationAssetType.IMAGE ||
            asset.role == ImageRole.REFERENCE ||
            coerced.indexOfLast { it.type == asset.type && it.role == asset.role } == index
    }
}

/**
 * 素材里同时有首帧 / 尾帧和参考素材，而 [capabilities] 不允许两者出现在同一个请求里。
 */
fun List<MediaCreationAsset>.mixesFramesWithReferences(capabilities: MediaGenerationCapabilities): Boolean {
    if (!capabilities.framesExcludeReferences) return false
    val (references, frames) = partition { it.type == MediaCreationAssetType.VIDEO || it.role == ImageRole.REFERENCE }
    return references.isNotEmpty() && frames.isNotEmpty()
}

/**
 * 草稿在 [capabilities] 下是否可以提交。
 */
fun MediaCreationDraft.canSubmit(capabilities: MediaGenerationCapabilities): Boolean =
    (prompt.isNotBlank() || (!capabilities.requiresPrompt && assets.isNotEmpty())) &&
        !assets.mixesFramesWithReferences(capabilities)

/**
 * 组装下发给适配器的请求。[inputUrls] 与 [assets] 一一对应，是适配器可以直接使用的地址
 * （公网地址或本地文件路径）。
 */
fun buildMediaGenerationRequest(
    prompt: String,
    params: MediaCreationParams,
    assets: List<MediaCreationAsset>,
    inputUrls: List<String>,
    capabilities: MediaGenerationCapabilities,
): MediaGenerationRequest {
    require(assets.size == inputUrls.size) { "assets and inputUrls must have the same size" }
    val supported = params.supportedBy(capabilities)
    return MediaGenerationRequest(
        prompt = prompt.takeIf(String::isNotBlank),
        inputs = assets.zip(inputUrls) { asset, url ->
            when (asset.type) {
                MediaCreationAssetType.IMAGE -> MediaGenerationInput.Image(url = url, role = asset.role)
                MediaCreationAssetType.VIDEO -> MediaGenerationInput.Video(url = url)
            }
        },
        count = supported.count,
        resolution = supported.resolution,
        aspectRatio = supported.aspectRatio,
        durationSeconds = supported.durationSeconds,
        generateAudio = supported.generateAudio,
        watermark = supported.watermark,
        seed = supported.seed,
        promptEnhancement = supported.promptEnhancement,
    )
}

/** 会话还没有标题时，用第一条提示词的开头命名。 */
fun deriveMediaCreationTitle(prompt: String): String =
    prompt.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty().take(TITLE_MAX_LENGTH)

private const val TITLE_MAX_LENGTH = 24
