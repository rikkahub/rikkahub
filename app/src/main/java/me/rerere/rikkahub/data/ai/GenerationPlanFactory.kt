package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatCommandException
import me.rerere.rikkahub.service.ChatFailure
import me.rerere.rikkahub.service.ChatFailureCode
import me.rerere.workspace.WorkspaceShellStatus

private const val PLAN_TAG = "GenerationPlanFactory"

data class ResolvedChatConfiguration(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
)

data class GenerationPlan(
    val configuration: ResolvedChatConfiguration,
    val memories: List<AssistantMemory>,
    val tools: List<Tool>,
    val inputTransformers: List<InputMessageTransformer>,
    val outputTransformers: List<OutputMessageTransformer>,
)

class GenerationPlanFactory(
    private val json: Json,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val templateTransformer: TemplateTransformer,
    private val ocrTransformer: OcrTransformer,
) {
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    fun resolve(conversation: Conversation): ResolvedChatConfiguration {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: throw ChatCommandException(
                ChatFailure(
                    code = ChatFailureCode.Configuration,
                    message = "Assistant not found",
                    conversationId = conversation.id,
                )
            )
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw ChatCommandException(
                ChatFailure(
                    code = ChatFailureCode.Configuration,
                    message = "Chat model not found",
                    conversationId = conversation.id,
                )
            )
        return ResolvedChatConfiguration(settings, assistant, model)
    }

    suspend fun create(conversation: Conversation): GenerationPlan {
        val configuration = resolve(conversation)
        val assistant = configuration.assistant
        val model = configuration.model
        val settings = configuration.settings

        val memories = if (!assistant.enableMemory) {
            emptyList()
        } else if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        }

        val tools = buildList {
            if (assistant.enableMemory) {
                val memoryAssistantId = if (assistant.useGlobalMemory) {
                    MemoryRepository.GLOBAL_MEMORY_ID
                } else {
                    assistant.id.toString()
                }
                addAll(
                    buildMemoryTools(
                        json = json,
                        onCreation = { memoryRepository.addMemory(memoryAssistantId, it) },
                        onUpdate = memoryRepository::updateContent,
                        onDelete = memoryRepository::deleteMemory,
                    )
                )
            }
            val useExternalSearch = shouldUseExternalWebSearch(assistant, model)
            if (useExternalSearch) addAll(createSearchTools(settings))
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(createSkillTools(assistant.enabledSkills, skillManager.listSkills()))
            }
            addAll(createMcpTools(conversation))
        }

        if (ModelAbility.TOOL !in model.abilities && tools.isNotEmpty()) {
            Log.w(PLAN_TAG, "Configured tools are unavailable for ${model.id}")
        }

        return GenerationPlan(
            configuration = configuration,
            memories = memories,
            tools = tools,
            inputTransformers = listOf(
                TimeReminderTransformer,
                PromptInjectionTransformer,
                PlaceholderTransformer,
                DocumentAsPromptTransformer,
                ocrTransformer,
                templateTransformer,
                workspaceReminderTransformer,
            ),
            outputTransformers = listOf(
                ThinkTagTransformer,
                Base64ImageToLocalFileTransformer,
                RegexOutputTransformer,
            ),
        )
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return emptyList()
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    private fun createMcpTools(conversation: Conversation): List<Tool> {
        val available = mcpManager.getAllAvailableTools()
        val invalidNames = available.map { it.second }.distinct().filter { name ->
            name.isEmpty() || !name.all { it.isLetterOrDigit() }
        }
        if (invalidNames.isNotEmpty()) {
            throw ChatCommandException(
                ChatFailure(
                    code = ChatFailureCode.Configuration,
                    message = "Invalid MCP server name: ${invalidNames.joinToString()}",
                    conversationId = conversation.id,
                )
            )
        }
        return available.map { (serverId, serverName, tool) ->
            Tool(
                name = "mcp__${serverName}__${tool.name}",
                description = tool.description ?: "",
                parameters = { tool.inputSchema },
                needsApproval = { tool.needsApproval },
                execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
            )
        }
    }
}

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean =
    assistant.enableWebSearch && BuiltInTools.Search !in model.tools
