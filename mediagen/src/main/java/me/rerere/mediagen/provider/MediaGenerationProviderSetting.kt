package me.rerere.mediagen.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

/**
 * 厂商级配置：只保存凭据和该厂商下的模型列表，同一份配置可以同时提供图像和视频模型。
 */
@Serializable
sealed class MediaGenerationProviderSetting {
    abstract val id: Uuid
    abstract val name: String
    abstract val apiKey: String
    abstract val baseUrl: String
    abstract val models: List<MediaGenerationModel>

    // What our adapter implements, not every API offered by this vendor.
    abstract val supportedKinds: Set<MediaKind>

    /**
     * 下载产出地址 [url] 时要带上的请求头。多数厂商给的是带签名的地址，不需要额外的头；
     * OpenRouter 的视频要凭 API Key 从它自己的接口取。
     */
    open fun downloadHeaders(url: String): Map<String, String> = emptyMap()

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
        apiKey: String = this.apiKey,
        baseUrl: String = this.baseUrl,
        models: List<MediaGenerationModel> = this.models,
    ): MediaGenerationProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override val id: Uuid = Uuid.random(),
        override val name: String = "OpenAI",
        override val apiKey: String = "",
        override val baseUrl: String = "https://api.openai.com/v1",
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "gpt-image-2", kind = MediaKind.IMAGE),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.IMAGE)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                models = models,
            )
        }
    }

    /**
     * 百炼按业务空间分配域名：[baseUrl] 里的 `{WorkspaceId}` 会被替换成 [workspaceId]。换地域时改 [baseUrl] 里的地域段；
     * 不含占位符的地址（如旧的 `https://dashscope.aliyuncs.com/api/v1`）原样使用，此时不需要 [workspaceId]。
     */
    @Serializable
    @SerialName("aliyun")
    data class Aliyun(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Aliyun",
        override val apiKey: String = "",
        val workspaceId: String = "",
        override val baseUrl: String = "https://$WORKSPACE_PLACEHOLDER.cn-beijing.maas.aliyuncs.com/api/v1",
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "wan2.7-image-pro", kind = MediaKind.IMAGE),
            MediaGenerationModel(modelId = "wan3.0-video", kind = MediaKind.VIDEO),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.IMAGE, MediaKind.VIDEO)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                models = models,
            )
        }

        companion object {
            const val WORKSPACE_PLACEHOLDER = "{WorkspaceId}"
        }
    }

    @Serializable
    @SerialName("volcengine")
    data class Volcengine(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Volcengine",
        override val apiKey: String = "",
        override val baseUrl: String = "https://ark.cn-beijing.volces.com/api/v3",
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "doubao-seedream-5-0-pro-260628", kind = MediaKind.IMAGE),
            MediaGenerationModel(modelId = "doubao-seedance-2-0-260128", kind = MediaKind.VIDEO),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.IMAGE, MediaKind.VIDEO)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                models = models,
            )
        }
    }

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override val id: Uuid = Uuid.random(),
        override val name: String = "MiniMax",
        override val apiKey: String = "",
        override val baseUrl: String = "https://api.minimaxi.com/v2",
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "MiniMax-H3", kind = MediaKind.VIDEO),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.VIDEO)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                models = models,
            )
        }
    }

    @Serializable
    @SerialName("openrouter")
    data class OpenRouter(
        override val id: Uuid = Uuid.random(),
        override val name: String = "OpenRouter",
        override val apiKey: String = "",
        override val baseUrl: String = "https://openrouter.ai/api/v1",
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "openai/gpt-image-2", kind = MediaKind.IMAGE),
            MediaGenerationModel(modelId = "google/veo-3.1", kind = MediaKind.VIDEO),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.IMAGE, MediaKind.VIDEO)

        // 密钥只发给 baseUrl 所在的站点，结果地址指向别处时原样下载
        override fun downloadHeaders(url: String): Map<String, String> {
            val target = url.toHttpUrlOrNull() ?: return emptyMap()
            val base = baseUrl.toHttpUrlOrNull() ?: return emptyMap()
            if (target.scheme != base.scheme || target.host != base.host || target.port != base.port) return emptyMap()
            return mapOf("Authorization" to "Bearer $apiKey")
        }

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                models = models,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Aliyun::class,
                Volcengine::class,
                MiniMax::class,
                OpenRouter::class,
            )
        }
    }
}
