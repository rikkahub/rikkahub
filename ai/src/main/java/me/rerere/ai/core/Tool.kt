package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: Boolean = false,
    /**
     * Whether this tool is offered to the model: included in the provider tool schema AND its
     * [systemPrompt] block. `false` keeps the tool fully resolvable by exact-name lookup — so a
     * persisted or in-flight tool call still executes — WITHOUT advertising it as a callable
     * capability. Used by the legacy `task` spawn alias (issue #355): only the canonical `agent`
     * tool is advertised, while `task` stays resolvable but hidden, removing the duplicate subagent
     * registry block and the ambiguous second delegation tool. The runtime filters on this flag at
     * the single seam where tools become provider-facing ([me.rerere.ai.runtime] generation path),
     * never at resolution, so advertised ⊆ executable holds by construction.
     */
    val advertised: Boolean = true,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
