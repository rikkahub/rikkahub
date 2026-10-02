package me.rerere.mediagen.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class MediaGenerationInput {
    abstract val extra: JsonObject

    /**
     * 视频接口要求 [url] 是公网地址。图像接口也接受本地文件路径：OpenAI 以文件形式上传（只接受本地文件），
     * 火山和阿里把本地文件编码成 data URI 内联进请求，公网地址和 data URI 则原样下发。
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        val role: ImageRole = ImageRole.REFERENCE,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    /** 阿里百炼万相 3.0 支持的文件输入。 */
    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    /** 阿里百炼万相 3.0 支持的公开网页输入。 */
    @Serializable
    @SerialName("web_page")
    data class WebPage(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    /**
     * 尚未进入公共抽象的供应商输入项，例如模型特有的样片任务引用。
     * 该对象会作为一个 content/media 元素原样交给适配器。
     */
    @Serializable
    @SerialName("raw")
    data class Raw(
        val value: JsonObject,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()
}

@Serializable
enum class ImageRole {
    @SerialName("first_frame")
    FIRST_FRAME,

    @SerialName("last_frame")
    LAST_FRAME,

    @SerialName("reference")
    REFERENCE,
}
