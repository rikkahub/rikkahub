package me.rerere.rikkahub.web.routes

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.ClaudeAuthType
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.common.json.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRoutesRedactionPropertyTest {

    private data class SettingsCase(
        val settings: Settings,
        val nonBlankSecrets: List<String>,
    )

    private enum class InjectionLocation {
        ASSISTANT_HEADER,
        ASSISTANT_BODY,
        MODEL_HEADER,
        MODEL_BODY,
        PROVIDER_OVERWRITE,
    }

    private enum class OverwriteTarget {
        API_KEY,
        PRIVATE_KEY,
        OAUTH_TOKEN,
        ACCESS_TOKEN,
    }

    private val arbProviderOverwriteTarget = Arb.element(OverwriteTarget.entries)
    private val arbInjectionLocation = Arb.element(InjectionLocation.entries)

    private val arbToken: Arb<String> =
        Arb.int(1_000_000, 9_999_999).map { "secret-$it" }

    private fun buildProviderOverwriteChain(
        depth: Int,
        suffix: String,
    ): Pair<ProviderSetting, List<String>> {
        if (depth <= 0) {
            val accessSecret = "provider-overwrite-access-$suffix"
            return ProviderSetting.OpenAI(mode = OpenAIMode.ChatGPT, 
                name = "ChatGPT-Overwrite",
                accessToken = accessSecret,
                models = emptyList(),
            ) to listOf(accessSecret)
        }

        val (nextProvider, secrets) = buildProviderOverwriteChain(depth - 1, suffix)
        val overwriteModel = Model(
            modelId = "overwrite-model-$depth-$suffix",
            providerOverwrite = nextProvider,
        )

        return when (depth) {
            3 -> {
                val openAiSecret = "provider-overwrite-openai-$suffix"
                ProviderSetting.OpenAI(
                    name = "OpenAI-Overwrite",
                    apiKey = openAiSecret,
                    models = listOf(overwriteModel),
                ) to (secrets + openAiSecret)
            }

            2 -> {
                val privateKeySecret = "provider-overwrite-privatekey-$suffix"
                ProviderSetting.Google(
                    name = "Google-Overwrite",
                    privateKey = privateKeySecret,
                    models = listOf(overwriteModel),
                ) to (secrets + privateKeySecret)
            }

            1 -> {
                val oauthTokenSecret = "provider-overwrite-oauth-$suffix"
                ProviderSetting.Claude(
                    name = "Claude-Overwrite",
                    oauthToken = oauthTokenSecret,
                    authType = ClaudeAuthType.OAuth,
                    models = listOf(overwriteModel),
                ) to (secrets + oauthTokenSecret)
            }

            else -> {
                val chatgptSecret = "provider-overwrite-access-$suffix"
                ProviderSetting.OpenAI(mode = OpenAIMode.ChatGPT, 
                    name = "ChatGPT-Overwrite",
                    accessToken = chatgptSecret,
                    models = listOf(overwriteModel),
                ) to (secrets + chatgptSecret)
            }
        }
    }

    private fun arbSettingsCase(maxProviderOverwriteDepth: Int): Arb<SettingsCase> = arbitrary {
        val nonce = Arb.int(1_000_000, 9_999_999).bind()
        val suffix = nonce.toString()
        val overwriteDepth = if (maxProviderOverwriteDepth <= 0) {
            0
        } else {
            Arb.int(0..maxProviderOverwriteDepth).bind()
        }
        val assistantHeader = "assistant-header-$suffix"
        val assistantBody = "assistant-body-$suffix"
        val modelHeader = "model-header-$suffix"
        val modelBody = "model-body-$suffix"
        val providerApiKey = "provider-openai-$suffix"

        val overwrite = if (overwriteDepth > 0) {
            buildProviderOverwriteChain(overwriteDepth, suffix)
        } else null

        val secrets = buildList {
            add(assistantHeader)
            add(assistantBody)
            add(modelHeader)
            add(modelBody)
            add(providerApiKey)
            overwrite?.second?.let { addAll(it) }
        }

        val providerModel = Model(
            modelId = "base-model-$suffix",
            customHeaders = listOf(CustomHeader("X-Model", modelHeader)),
            customBodies = listOf(CustomBody("x-body", JsonPrimitive(modelBody))),
            providerOverwrite = overwrite?.first,
        )

        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "OpenAI-$suffix",
                    apiKey = providerApiKey,
                    models = listOf(providerModel),
                )
            ),
            assistants = listOf(
                Assistant(
                    customHeaders = listOf(CustomHeader("X-Assistant", assistantHeader)),
                    customBodies = listOf(CustomBody("x-body", JsonPrimitive(assistantBody))),
                )
            ),
            searchServices = listOf(),
            ttsProviders = listOf(),
            asrProviders = listOf(),
            mcpServers = listOf(),
        )

        SettingsCase(settings = settings, nonBlankSecrets = secrets)
    }

    private fun baseSettingsForMetamorphic(): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(
                name = "OpenAI-base",
                apiKey = "base-provider",
                models = listOf(
                    Model(
                        modelId = "base-model",
                        customHeaders = listOf(CustomHeader("X-Model", "base-model-header")),
                        customBodies = listOf(CustomBody("x-body", JsonPrimitive("base-model-body"))),
                        providerOverwrite = ProviderSetting.OpenAI(mode = OpenAIMode.ChatGPT, 
                            name = "ChatGPT-base",
                            accessToken = "base-overwrite-token",
                            models = emptyList(),
                        ),
                    ),
                ),
            ),
        ),
        assistants = listOf(
            Assistant(
                customHeaders = listOf(CustomHeader("X-Assistant", "base-assistant-header")),
                customBodies = listOf(CustomBody("x-body", JsonPrimitive("base-assistant-body"))),
            )
        ),
        searchServices = listOf(),
        ttsProviders = listOf(),
        asrProviders = listOf(),
        mcpServers = listOf(),
    )

    private fun withInjectedToken(
        settings: Settings,
        token: String,
        location: InjectionLocation,
        overwriteTarget: OverwriteTarget,
    ): Settings {
        val assistant = settings.assistants.first()
        val provider = settings.providers.first()
        val model = provider.models.first()

        return when (location) {
            InjectionLocation.ASSISTANT_HEADER -> {
                settings.copy(
                    assistants = listOf(
                        assistant.copy(customHeaders = listOf(CustomHeader("X-Assistant", token)))
                    )
                )
            }

            InjectionLocation.ASSISTANT_BODY -> {
                settings.copy(
                    assistants = listOf(
                        assistant.copy(customBodies = listOf(CustomBody("x-body", JsonPrimitive(token))))
                    )
                )
            }

            InjectionLocation.MODEL_HEADER -> {
                settings.copy(
                    providers = listOf(
                        provider.copyProvider(
                            models = listOf(
                                model.copy(customHeaders = listOf(CustomHeader("X-Model", token)))
                            )
                        )
                    )
                )
            }

            InjectionLocation.MODEL_BODY -> {
                settings.copy(
                    providers = listOf(
                        provider.copyProvider(
                            models = listOf(
                                model.copy(customBodies = listOf(CustomBody("x-body", JsonPrimitive(token))))
                            )
                        )
                    )
                )
            }

            InjectionLocation.PROVIDER_OVERWRITE -> {
                val overwrite = when (overwriteTarget) {
                    OverwriteTarget.API_KEY -> ProviderSetting.OpenAI(apiKey = token, models = emptyList())
                    OverwriteTarget.PRIVATE_KEY -> ProviderSetting.Google(privateKey = token, models = emptyList())
                    OverwriteTarget.OAUTH_TOKEN -> ProviderSetting.Claude(
                        oauthToken = token,
                        authType = ClaudeAuthType.OAuth,
                        models = emptyList(),
                    )

                    OverwriteTarget.ACCESS_TOKEN -> ProviderSetting.OpenAI(mode = OpenAIMode.ChatGPT, accessToken = token, models = emptyList())
                }

                settings.copy(
                    providers = listOf(
                        provider.copyProvider(
                            models = listOf(
                                model.copy(providerOverwrite = overwrite)
                            )
                        )
                    )
                )
            }
        }
    }

    private fun encodeSanitized(settings: Settings): String =
        JsonInstant.encodeToString(settings.sanitizeForWeb())

    private fun assertNoSecrets(json: String, secrets: List<String>) {
        secrets.forEach { secret ->
            assertFalse("redacted output leaked '$secret'", json.contains(secret))
        }
    }

    @Test
    fun `sanitizeForWeb redacts all injected nonblank secrets from reachable settings`() {
        runBlocking {
            checkAll(200, arbSettingsCase(maxProviderOverwriteDepth = 3)) { case ->
                val sanitized = encodeSanitized(case.settings)
                assertNoSecrets(sanitized, case.nonBlankSecrets)
            }
        }
    }

    @Test
    fun `sanitizeForWeb removes a fresh secret injected in any nested target`() {
        runBlocking {
            checkAll(
                200,
                arbToken,
                arbInjectionLocation,
                arbProviderOverwriteTarget,
            ) { token, target, overwriteTarget ->
                val injected = withInjectedToken(
                    settings = baseSettingsForMetamorphic(),
                    token = token,
                    location = target,
                    overwriteTarget = overwriteTarget,
                )
                val sanitized = encodeSanitized(injected)

                assertFalse("token should not be visible after redaction", sanitized.contains(token))
            }
        }
    }

    @Test
    fun `sanitizeForWeb handles boundary shapes and boundary secrets`() {
        val emptySettingsJson = encodeSanitized(Settings())
        assertTrue(emptySettingsJson.isNotEmpty())

        val emptyCollections = Settings(
            providers = listOf(),
            assistants = listOf(),
            searchServices = listOf(),
            ttsProviders = listOf(),
            asrProviders = listOf(),
            mcpServers = listOf(),
        )
        val emptyCollectionsJson = encodeSanitized(emptyCollections)
        assertTrue(emptyCollectionsJson.contains("\"assistants\":[]"))
        assertTrue(emptyCollectionsJson.contains("\"providers\":[]"))

        val nullProviderOverwrite = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "OpenAI-null",
                    apiKey = "provider-openai-null-secret",
                    models = listOf(
                        Model(
                            modelId = "null-overwrite-model",
                            providerOverwrite = null,
                        )
                    ),
                ),
            ),
            assistants = listOf(
                Assistant(
                    customHeaders = listOf(CustomHeader("X-Assistant", "assistant-null-secret")),
                )
            ),
        )
        val nullOverwriteJson = encodeSanitized(nullProviderOverwrite)
        assertFalse(nullOverwriteJson.contains("provider-openai-null-secret"))
        assertFalse(nullOverwriteJson.contains("assistant-null-secret"))

        val chainDepth3 = buildProviderOverwriteChain(3, "boundary-depth-3")
        val depth3ProviderOverwrite = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "OpenAI-depth3",
                    apiKey = "provider-depth3-secret",
                    models = listOf(
                        Model(
                            modelId = "depth3-model",
                            providerOverwrite = chainDepth3.first,
                        )
                    ),
                )
            ),
        )
        val depth3Json = encodeSanitized(depth3ProviderOverwrite)
        assertNoSecrets(depth3Json, listOf("provider-depth3-secret") + chainDepth3.second)

        val blankAndJsonNull = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    apiKey = "",
                    models = listOf(
                        Model(
                            modelId = "blank-model",
                            customHeaders = listOf(CustomHeader("X-Model", "")),
                            customBodies = listOf(CustomBody("x-body", JsonNull)),
                            providerOverwrite = ProviderSetting.OpenAI(mode = OpenAIMode.ChatGPT, 
                                accessToken = "",
                                models = emptyList(),
                            ),
                        ),
                    ),
                ),
            ),
            assistants = listOf(
                Assistant(
                    customHeaders = listOf(CustomHeader("X-Assistant", "")),
                    customBodies = listOf(CustomBody("x-body", JsonNull)),
                ),
            ),
            searchServices = listOf(),
            ttsProviders = listOf(),
            asrProviders = listOf(),
            mcpServers = listOf(),
        )
        val blankJson = encodeSanitized(blankAndJsonNull)
        assertTrue(blankJson.contains("\"name\":\"X-Model\""))
        assertTrue(blankJson.contains("\"name\":\"X-Assistant\""))
        assertTrue(blankJson.contains("\"key\":\"x-body\""))
        assertTrue(blankJson.contains("\"value\":\"\""))
    }

    @Test
    fun `sanitizeForWeb does not mutate settings while redacting and changes output when secrets exist`() {
        runBlocking {
            checkAll(200, arbSettingsCase(maxProviderOverwriteDepth = 3)) { case ->
                val originalJsonBefore = JsonInstant.encodeToString(case.settings)
                val sanitizedJson = encodeSanitized(case.settings)
                val originalJsonAfter = JsonInstant.encodeToString(case.settings)

                assertEquals(originalJsonBefore, originalJsonAfter)
                assertNotEquals(sanitizedJson, originalJsonBefore)
                assertNoSecrets(sanitizedJson, case.nonBlankSecrets)
            }
        }
    }
}
