package me.rerere.ai.ui

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Shared generators for [UIMessage] / [UIMessagePart], internal to the :ai test sourceset.
 *
 * DETERMINISM: every timestamp (Reasoning.createdAt/finishedAt, UIMessage.createdAt/finishedAt)
 * is Arb-generated from a sane epoch-millis range, never left to the Clock.now() default — a
 * default-clocked value would differ between encode and decode and make equality flaky.
 */
object UIMessageArbs {

    private val arbKotlinUuid: Arb<Uuid> = Arb.uuid().map { Uuid.parse(it.toString()) }

    private val arbShortString: Arb<String> = Arb.string(0..16)

    // 2000-01-01 .. 2100-01-01 in epoch millis: safe, well within Instant/LocalDateTime range.
    private val saneEpochMillis: Arb<Long> = Arb.long(946_684_800_000L..4_102_444_800_000L)

    private val FIXED_TZ = TimeZone.UTC

    private val arbInstant: Arb<Instant> =
        saneEpochMillis.map { Instant.fromEpochMilliseconds(it) }

    private val arbLocalDateTime: Arb<LocalDateTime> =
        arbInstant.map { it.toLocalDateTime(FIXED_TZ) }

    // Keep metadata simple: null or a tiny string-only object. Arbitrary JsonObjects could carry
    // doubles whose re-encoded textual form differs, which is a serialization concern orthogonal
    // to the UIMessage roundtrip under test.
    private val arbMetadata: Arb<JsonObject?> = Arb.choice(
        Arb.of(listOf<JsonObject?>(null)),
        arbShortString.map { JsonObject(mapOf("k" to JsonPrimitive(it))) },
    )

    private val arbText: Arb<UIMessagePart.Text> = arbitrary {
        UIMessagePart.Text(text = arbShortString.bind(), metadata = arbMetadata.bind())
    }

    private val arbImage: Arb<UIMessagePart.Image> = arbitrary {
        UIMessagePart.Image(url = arbShortString.bind(), metadata = arbMetadata.bind())
    }

    private val arbDocument: Arb<UIMessagePart.Document> = arbitrary {
        UIMessagePart.Document(
            url = arbShortString.bind(),
            fileName = arbShortString.bind(),
            mime = arbShortString.bind(),
            metadata = arbMetadata.bind(),
        )
    }

    private val arbReasoning: Arb<UIMessagePart.Reasoning> = arbitrary {
        UIMessagePart.Reasoning(
            reasoning = arbShortString.bind(),
            createdAt = arbInstant.bind(),
            // finishedAt is deliberately kept non-null. Reasoning.finishedAt's constructor DEFAULT
            // is Clock.System.now() (non-null), and the :ai json uses explicitNulls=false: a null
            // finishedAt is OMITTED on encode and then restored to that non-null default on decode,
            // so finishedAt=null is irrecoverable by design. That asymmetry is a documented
            // serialization fragility (reported), not exercised here so the roundtrip stays honest.
            finishedAt = arbInstant.bind(),
            metadata = arbMetadata.bind(),
        )
    }

    private val arbApprovalState: Arb<ToolApprovalState> = Arb.choice(
        Arb.of(listOf<ToolApprovalState>(ToolApprovalState.Auto)),
        Arb.of(listOf<ToolApprovalState>(ToolApprovalState.Pending)),
        Arb.of(listOf<ToolApprovalState>(ToolApprovalState.Approved)),
        arbShortString.map { ToolApprovalState.Denied(it) },
        arbShortString.map { ToolApprovalState.Answered(it) },
    )

    private val arbTool: Arb<UIMessagePart.Tool> = arbitrary {
        UIMessagePart.Tool(
            toolCallId = arbShortString.bind(),
            toolName = arbShortString.bind(),
            input = arbShortString.bind(),
            // output limited to Text parts to keep the part-tree shallow and serializable.
            output = Arb.list(arbText, 0..2).bind(),
            approvalState = arbApprovalState.bind(),
            metadata = arbMetadata.bind(),
        )
    }

    private val arbPart: Arb<UIMessagePart> =
        Arb.choice(arbText, arbImage, arbReasoning, arbDocument, arbTool)

    private val arbUrlCitation: Arb<UIMessageAnnotation.UrlCitation> = arbitrary {
        UIMessageAnnotation.UrlCitation(title = arbShortString.bind(), url = arbShortString.bind())
    }

    private val arbTokenUsage: Arb<TokenUsage> = arbitrary {
        TokenUsage(
            promptTokens = Arb.long(0L..100_000L).map { it.toInt() }.bind(),
            completionTokens = Arb.long(0L..100_000L).map { it.toInt() }.bind(),
            cachedTokens = Arb.long(0L..100_000L).map { it.toInt() }.bind(),
            totalTokens = Arb.long(0L..200_000L).map { it.toInt() }.bind(),
        )
    }

    val arbUIMessage: Arb<UIMessage> = arbitrary {
        UIMessage(
            id = arbKotlinUuid.bind(),
            role = Arb.enum<MessageRole>().bind(),
            parts = Arb.list(arbPart, 0..4).bind(),
            annotations = Arb.list(arbUrlCitation, 0..2).bind(),
            createdAt = arbLocalDateTime.bind(),
            finishedAt = Arb.choice(
                Arb.of(listOf<LocalDateTime?>(null)),
                arbLocalDateTime.map { it as LocalDateTime? },
            ).bind(),
            modelId = Arb.choice(
                Arb.of(listOf<Uuid?>(null)),
                arbKotlinUuid.map { it as Uuid? },
            ).bind(),
            usage = Arb.choice(
                Arb.of(listOf<TokenUsage?>(null)),
                arbTokenUsage.map { it as TokenUsage? },
            ).bind(),
            translation = Arb.choice(
                Arb.of(listOf<String?>(null)),
                arbShortString.map { it as String? },
            ).bind(),
        )
    }

    val arbMessages: Arb<List<UIMessage>> = Arb.list(arbUIMessage, 0..3)
}
