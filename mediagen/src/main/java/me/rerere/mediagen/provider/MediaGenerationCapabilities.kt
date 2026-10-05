package me.rerere.mediagen.provider

import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaKind

/** [MediaGenerationRequest] 里可选的公共字段。 */
enum class MediaGenerationParameter {
    /** [MediaGenerationRequest.count]：一次请求的产出数量，目前只有部分图像接口支持。 */
    COUNT,

    /** [MediaGenerationRequest.resolution]：视频是清晰度档位（`1080P`），图像是档位或像素尺寸（`2K`、`1536x1024`）。 */
    RESOLUTION,

    /** [MediaGenerationRequest.aspectRatio]：宽高比（`16:9`），部分接口还接受 `adaptive`。 */
    ASPECT_RATIO,

    /** [MediaGenerationRequest.durationSeconds]：视频时长，`-1` 表示交给模型决定。 */
    DURATION,

    /** [MediaGenerationRequest.generateAudio]：视频是否带声音。 */
    GENERATE_AUDIO,

    /** [MediaGenerationRequest.watermark]：是否加上「AI 生成」水印。 */
    WATERMARK,

    /** [MediaGenerationRequest.seed]：随机种子，用来尽量复现同一个结果。 */
    SEED,

    /** [MediaGenerationRequest.promptEnhancement]：是否让厂商先改写提示词再生成。 */
    PROMPT_ENHANCEMENT,
}

/**
 * 厂商适配器在一种 [MediaKind] 下接受的请求形态，供上层决定展示哪些选项、提交前怎样准备素材。
 *
 * 描述的是适配器而不是具体模型：不在 [parameters] 里的公共字段会被适配器直接拒绝，
 * 在里面的字段是否被某个模型接受、取值范围是什么，仍由接口决定。其余各项来自厂商文档里对整个接口的约定。
 */
data class MediaGenerationCapabilities(
    val parameters: Set<MediaGenerationParameter>,
    /** 接口没有默认值、需要调用方给出的字段，是 [parameters] 的子集。 */
    val requiredParameters: Set<MediaGenerationParameter> = emptySet(),
    /** 可用的图片输入角色，为空表示不接受图片输入。 */
    val imageRoles: Set<ImageRole>,
    val videoInput: Boolean = false,
    /** 首帧 / 尾帧和参考素材（参考图、参考视频）是两种生成方式，不能出现在同一个请求里。 */
    val framesExcludeReferences: Boolean = false,
    /** 只用公共字段时提示词是否必填。 */
    val requiresPrompt: Boolean = false,
    /** 素材必须是公网地址；为 false 时图片输入可以直接传本地文件路径。 */
    val requiresRemoteInputs: Boolean = false,
)

/** 适配器没有实现 [kind] 时返回 null。 */
fun MediaGenerationProviderSetting.capabilities(kind: MediaKind): MediaGenerationCapabilities? =
    when (this) {
        is MediaGenerationProviderSetting.OpenAI -> when (kind) {
            MediaKind.IMAGE -> OPENAI_IMAGE
            MediaKind.VIDEO -> null
        }

        is MediaGenerationProviderSetting.Aliyun -> when (kind) {
            MediaKind.IMAGE -> ALIYUN_IMAGE
            MediaKind.VIDEO -> ALIYUN_VIDEO
        }

        is MediaGenerationProviderSetting.Volcengine -> when (kind) {
            MediaKind.IMAGE -> VOLCENGINE_IMAGE
            MediaKind.VIDEO -> VOLCENGINE_VIDEO
        }

        is MediaGenerationProviderSetting.MiniMax -> when (kind) {
            MediaKind.IMAGE -> null
            MediaKind.VIDEO -> MINIMAX_VIDEO
        }

        is MediaGenerationProviderSetting.OpenRouter -> when (kind) {
            MediaKind.IMAGE -> OPENROUTER_IMAGE
            MediaKind.VIDEO -> OPENROUTER_VIDEO
        }
    }

// 图像接口不区分图片角色，统一当作参考图
private val REFERENCE_ONLY = setOf(ImageRole.REFERENCE)
private val ALL_IMAGE_ROLES = ImageRole.entries.toSet()

private val OPENAI_IMAGE = MediaGenerationCapabilities(
    parameters = setOf(MediaGenerationParameter.COUNT, MediaGenerationParameter.RESOLUTION),
    imageRoles = REFERENCE_ONLY,
    requiresPrompt = true,
)

private val ALIYUN_IMAGE = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.COUNT,
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.WATERMARK,
        MediaGenerationParameter.SEED,
        MediaGenerationParameter.PROMPT_ENHANCEMENT,
    ),
    imageRoles = REFERENCE_ONLY,
    requiresPrompt = true,
)

private val ALIYUN_VIDEO = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
        MediaGenerationParameter.GENERATE_AUDIO,
        MediaGenerationParameter.WATERMARK,
        MediaGenerationParameter.SEED,
        MediaGenerationParameter.PROMPT_ENHANCEMENT,
    ),
    imageRoles = ALL_IMAGE_ROLES,
    videoInput = true,
    framesExcludeReferences = true,
    requiresRemoteInputs = true,
)

private val VOLCENGINE_IMAGE = MediaGenerationCapabilities(
    parameters = setOf(MediaGenerationParameter.RESOLUTION, MediaGenerationParameter.WATERMARK),
    imageRoles = REFERENCE_ONLY,
    // 只有图层拆分可以不带提示词，而它要通过 extraParameters 开启
    requiresPrompt = true,
)

private val VOLCENGINE_VIDEO = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
        MediaGenerationParameter.GENERATE_AUDIO,
        MediaGenerationParameter.WATERMARK,
        MediaGenerationParameter.SEED,
    ),
    imageRoles = ALL_IMAGE_ROLES,
    videoInput = true,
    framesExcludeReferences = true,
    requiresRemoteInputs = true,
)

private val MINIMAX_VIDEO = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
        MediaGenerationParameter.WATERMARK,
    ),
    // 比例只在文生视频时必填（且不能是 adaptive），带首尾帧时会被忽略，这里统一要求给出
    requiredParameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
    ),
    imageRoles = ALL_IMAGE_ROLES,
    videoInput = true,
    framesExcludeReferences = true,
    requiresPrompt = true,
    requiresRemoteInputs = true,
)

private val OPENROUTER_IMAGE = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.COUNT,
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.SEED,
    ),
    imageRoles = REFERENCE_ONLY,
    requiresPrompt = true,
)

private val OPENROUTER_VIDEO = MediaGenerationCapabilities(
    parameters = setOf(
        MediaGenerationParameter.RESOLUTION,
        MediaGenerationParameter.ASPECT_RATIO,
        MediaGenerationParameter.DURATION,
        MediaGenerationParameter.GENERATE_AUDIO,
        MediaGenerationParameter.SEED,
    ),
    imageRoles = ALL_IMAGE_ROLES,
    videoInput = true,
    // 两者同时出现时接口不报错，但只按首尾帧生成，参考素材被忽略
    framesExcludeReferences = true,
    requiresRemoteInputs = true,
)
