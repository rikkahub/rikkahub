package me.rerere.rikkahub.ui.components

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.ClaudeAuthType
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.OpenAIMode
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * Minimal DUPLICATE of the :ai ProviderSetting generators, scoped to the :app test sourceset.
 *
 * Cross-module reuse is deliberately avoided: the :ai roundtrip test depends on
 * me.rerere.ai.util.json (internal), and the repo has no java-test-fixtures convention, so the
 * :ai test object is not visible here. Wiring testFixtures would exceed a tests-only change.
 * This copy generates non-empty model lists so the models-stripping behaviour of encodeForShare
 * is actually exercised by the property in [ShareSheetPropertyTest].
 */
object ProviderSettingTestArb {

    private val arbKotlinUuid: Arb<Uuid> = Arb.uuid().map { Uuid.parse(it.toString()) }
    private val arbShortString: Arb<String> = Arb.string(0..16)

    private val arbBalanceOption: Arb<BalanceOption> = arbitrary {
        BalanceOption(
            enabled = Arb.boolean().bind(),
            apiPath = Arb.string(0..12).bind(),
            resultPath = Arb.string(0..24).bind(),
        )
    }

    private val arbModel: Arb<Model> = arbitrary {
        Model(
            modelId = arbShortString.bind(),
            displayName = arbShortString.bind(),
            id = arbKotlinUuid.bind(),
            type = Arb.enum<ModelType>().bind(),
            inputModalities = Arb.list(Arb.enum<Modality>(), 1..2).bind(),
            outputModalities = Arb.list(Arb.enum<Modality>(), 1..2).bind(),
            abilities = Arb.list(Arb.enum<ModelAbility>(), 0..2).bind(),
            providerOverwrite = null,
        )
    }

    // Non-empty so the strip is observable.
    private val arbModels: Arb<List<Model>> = Arb.list(arbModel, 1..3)

    private val arbOpenAI: Arb<ProviderSetting.OpenAI> = arbitrary {
        ProviderSetting.OpenAI(
            id = arbKotlinUuid.bind(),
            enabled = Arb.boolean().bind(),
            name = arbShortString.bind(),
            models = arbModels.bind(),
            balanceOption = arbBalanceOption.bind(),
            apiKey = arbShortString.bind(),
            baseUrl = arbShortString.bind(),
            chatCompletionsPath = arbShortString.bind(),
            useResponseApi = Arb.boolean().bind(),
            includeHistoryReasoning = Arb.boolean().bind(),
            mode = Arb.enum<OpenAIMode>().bind(),
            accessToken = arbShortString.bind(),
        )
    }

    private val arbGoogle: Arb<ProviderSetting.Google> = arbitrary {
        ProviderSetting.Google(
            id = arbKotlinUuid.bind(),
            enabled = Arb.boolean().bind(),
            name = arbShortString.bind(),
            models = arbModels.bind(),
            balanceOption = arbBalanceOption.bind(),
            apiKey = arbShortString.bind(),
            baseUrl = arbShortString.bind(),
            vertexAI = Arb.boolean().bind(),
            useServiceAccount = Arb.boolean().bind(),
            privateKey = arbShortString.bind(),
            serviceAccountEmail = arbShortString.bind(),
            location = arbShortString.bind(),
            projectId = arbShortString.bind(),
        )
    }

    private val arbClaude: Arb<ProviderSetting.Claude> = arbitrary {
        ProviderSetting.Claude(
            id = arbKotlinUuid.bind(),
            enabled = Arb.boolean().bind(),
            name = arbShortString.bind(),
            models = arbModels.bind(),
            balanceOption = arbBalanceOption.bind(),
            apiKey = arbShortString.bind(),
            baseUrl = arbShortString.bind(),
            promptCaching = Arb.boolean().bind(),
            promptCacheTtl = Arb.enum<ClaudePromptCacheTtl>().bind(),
            authType = Arb.enum<ClaudeAuthType>().bind(),
            oauthToken = arbShortString.bind(),
            oauthContext1M = Arb.boolean().bind(),
        )
    }

    val arbProviderSetting: Arb<ProviderSetting> =
        Arb.choice(arbOpenAI, arbGoogle, arbClaude)
}
