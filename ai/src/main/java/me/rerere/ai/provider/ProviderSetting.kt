package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径
    val resultPath: String = "data.total_usage", // 余额获取JSON路径
)

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
enum class ClaudeAuthType {
    @SerialName("api_key")
    ApiKey,

    @SerialName("oauth")
    OAuth
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption

    abstract val builtIn: Boolean

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        builtIn: Boolean = this.builtIn,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var includeHistoryReasoning: Boolean = true,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                balanceOption = balanceOption,
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
        var antigravity: Boolean = false, // route via the managed cloudcode backend (OAuth refresh token) instead of an API key
        var antigravityRefreshToken: String = "", // only for antigravity mode; paste-only
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                balanceOption = balanceOption
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
        var authType: ClaudeAuthType = ClaudeAuthType.ApiKey,
        var oauthToken: String = "",
        var oauthContext1M: Boolean = false,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                builtIn = builtIn,
            )
        }
    }

    @Serializable
    @SerialName("chatgpt")
    data class ChatGPT(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "ChatGPT",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        // Paste-only ChatGPT subscription (Codex backend) access token; no in-app login/refresh.
        var accessToken: String = "",
        var baseUrl: String = "https://chatgpt.com/backend-api/codex",
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                builtIn = builtIn,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
                ChatGPT::class,
            )
        }
    }
}
