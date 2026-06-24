package me.rerere.ai.provider

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import kotlin.uuid.Uuid

/**
 * Shared generators for [ProviderSetting] and its parts, internal to the :ai test sourceset.
 *
 * Why internal-only and duplicated in :app: the roundtrip property under test uses
 * me.rerere.ai.util.json which is `internal`, so the :ai property must live in this module.
 * The repo has no java-test-fixtures convention, so :app cannot consume this object; :app keeps
 * a minimal copy (see ProviderSettingTestArb). Linus-minimal: a tiny duplicate beats wiring a
 * testFixtures plugin in a tests-only change.
 */
object ProviderSettingArbs {

    // kotest's Arb.uuid() yields a java.util.UUID; map to kotlin.uuid.Uuid via its string form.
    private val arbKotlinUuid: Arb<Uuid> = Arb.uuid().map { Uuid.parse(it.toString()) }

    private val arbShortString: Arb<String> = Arb.string(0..16)

    private val arbBalanceOption: Arb<BalanceOption> = arbitrary {
        BalanceOption(
            enabled = Arb.boolean().bind(),
            apiPath = Arb.string(0..12).bind(),
            resultPath = Arb.string(0..24).bind(),
        )
    }

    /**
     * providerOverwrite is forced to null: Model nests ProviderSetting?, and a recursive Arb would
     * blow the stack. The field is irrelevant to the serialization properties under test.
     */
    val arbModel: Arb<Model> = arbitrary {
        Model(
            modelId = arbShortString.bind(),
            displayName = arbShortString.bind(),
            id = arbKotlinUuid.bind(),
            type = Arb.enum<ModelType>().bind(),
            customHeaders = emptyList(),
            customBodies = emptyList(),
            inputModalities = Arb.list(Arb.enum<Modality>(), 1..2).bind(),
            outputModalities = Arb.list(Arb.enum<Modality>(), 1..2).bind(),
            abilities = Arb.list(Arb.enum<ModelAbility>(), 0..2).bind(),
            tools = emptySet(),
            providerOverwrite = null,
        )
    }

    val arbModels: Arb<List<Model>> = Arb.list(arbModel, 0..3)

    val arbOpenAI: Arb<ProviderSetting.OpenAI> = arbitrary {
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
            azureApiVersion = arbShortString.bind(),
        )
    }

    val arbGoogle: Arb<ProviderSetting.Google> = arbitrary {
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

    val arbClaude: Arb<ProviderSetting.Claude> = arbitrary {
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
