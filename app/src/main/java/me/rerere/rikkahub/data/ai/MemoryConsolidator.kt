package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.MemoryConsolidationConversation
import me.rerere.rikkahub.data.ai.prompts.buildMemoryConsolidationInput
import me.rerere.rikkahub.data.ai.prompts.buildMemoryConsolidationPrompt
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.tools.createMemoryConversationTools
import me.rerere.rikkahub.data.ai.tools.dialogueMessages
import me.rerere.rikkahub.data.ai.tools.memoryWritePath
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.dao.MemoryConsolidationDAO
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.common.android.Logging
import kotlin.uuid.Uuid

private const val TAG = "MemoryConsolidator"

private const val MAX_STEPS = 16
private const val FULL_REVIEW_MAX_STEPS = 32
private const val MAX_CONVERSATIONS_PER_RUN = 8

// 找待整理的对话时最多读这么多个候选：每个都要把消息读出来，刚开启记忆时候选可能有几百个
private const val MAX_SCANNED_CONVERSATIONS = 50
private const val MAX_TURNS_PER_CONVERSATION = 12
private const val MAX_TRANSCRIPT_LENGTH = 60_000
private const val RECENT_CONTEXT_MESSAGES = 6
private const val RECENT_CONTEXT_MESSAGE_LENGTH = 500
private const val USER_MESSAGE_LENGTH = 8000
private const val ASSISTANT_MESSAGE_LENGTH = 2000

// 更早的对话不再补整理：刚开启记忆或导入了一批旧对话时，不该把整段历史都交给模型
private const val PENDING_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/**
 * 后台记忆整理：把各个对话里上次整理之后新增的轮次交给一个只带记忆工具和对话查询工具的 agent，
 * 由它决定哪些事实值得长期保存。
 *
 * 主对话默认不写记忆，只在用户明确要求时才写；日常的积累都靠这里。
 * 什么时候整理由 [MemoryConsolidationScheduler] 决定。
 */
class MemoryConsolidator(
    private val generationLoop: GenerationLoop,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val consolidationDao: MemoryConsolidationDAO,
    private val settingsStore: SettingsStore,
) : MemoryConsolidationWorker {

    override suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation? =
        withContext(Dispatchers.IO) {
            val memoryId = settingsStore.awaitLoaded().memoryIdOf(conversation) ?: return@withContext null
            val pending = loadPendingTurns(conversation)
            if (pending.turns.isEmpty()) {
                null
            } else {
                PendingMemoryConversation(conversation.id, memoryId, pending.totalTurns)
            }
        }

    override suspend fun memoryIdsWithUpdates(): List<String> = withContext(Dispatchers.IO) {
        val assistants = settingsStore.awaitLoaded().assistants.filter { it.enableMemory }
        if (assistants.isEmpty()) return@withContext emptyList()
        val updated = consolidationDao.getAssistantIdsWithUpdatedConversations(
            assistantIds = assistants.map { it.id.toString() },
            since = System.currentTimeMillis() - PENDING_WINDOW_MS,
        ).toSet()
        assistants.filter { it.id.toString() in updated }.map { it.memoryId }.distinct()
    }

    override suspend fun findPending(memoryId: String): List<PendingMemoryConversation> =
        withContext(Dispatchers.IO) {
            val assistantIds = settingsStore.awaitLoaded().assistants
                .filter { it.enableMemory && it.memoryId == memoryId }
                .map { it.id.toString() }
            if (assistantIds.isEmpty()) return@withContext emptyList()
            val candidates = consolidationDao.getUpdatedConversationIds(
                assistantIds = assistantIds,
                since = System.currentTimeMillis() - PENDING_WINDOW_MS,
                limit = MAX_SCANNED_CONVERSATIONS,
            )
            collectPending(candidates, limit = MAX_CONVERSATIONS_PER_RUN) { id ->
                conversationRepo.getConversationById(Uuid.parse(id))?.let { pendingOf(it) }
            }
        }

    override suspend fun markConsolidated(conversation: Conversation) = withContext(Dispatchers.IO) {
        advance(conversation.id, maxOf(System.currentTimeMillis(), conversation.updateAt.toEpochMilli()))
    }

    override suspend fun consolidate(
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun? = withContext(Dispatchers.IO) {
        try {
            consolidateStore(memoryId, conversationIds, fullReview)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logging.log(TAG, "consolidate $memoryId: $e")
            throw e
        }
    }

    private suspend fun consolidateStore(
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun? {
        val settings = settingsStore.awaitLoaded()
        val assistants = settings.assistants.filter { it.enableMemory && it.memoryId == memoryId }
        if (assistants.isEmpty()) return null
        val assistantIds = assistants.map { it.id }.toSet()

        val batches = conversationIds
            .mapNotNull { conversationRepo.getConversationById(it) }
            .filter { it.assistantId in assistantIds }
            .sortedByDescending { it.updateAt }
            .mapNotNull { conversation ->
                val pending = loadPendingTurns(conversation)
                if (pending.turns.isEmpty()) null else Batch(conversation, pending)
            }
            .withinTranscriptBudget()
        val files = memoryRepository.getFiles(memoryId)
        // 没有新的轮次时，只有手动清理且记忆库里有东西才值得调用模型
        if (batches.isEmpty() && (!fullReview || files.isEmpty())) return null

        val model = settings.consolidationModel(batches.firstOrNull()?.conversation, assistants.first())
            ?: error("No model available")
        // 自动整理只增改；删文件只在用户手动要求清理整个记忆库时才允许
        val tools = buildMemoryTools(memoryRepository, memoryId)
            .filter { fullReview || it.name != MemoryToolNames.DELETE } +
            createMemoryConversationTools(conversationRepo, assistantIds)

        var messages = emptyList<UIMessage>()
        generationLoop.generateText(
            settings = settings,
            model = model,
            messages = listOf(
                UIMessage.user(
                    buildMemoryConsolidationInput(
                        files = files,
                        conversations = batches.map { it.input },
                    )
                )
            ),
            assistant = Assistant(
                systemPrompt = buildMemoryConsolidationPrompt(fullReview),
                streamOutput = false,
                reasoningLevel = settings.fastModelReasoningLevel,
            ),
            tools = tools,
            maxSteps = if (fullReview) FULL_REVIEW_MAX_STEPS else MAX_STEPS,
            // 只用来当请求的会话 ID：整次整理的每一步用同一个，服务端才能命中前面几步的缓存
            conversationId = Uuid.random(),
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> messages = chunk.messages
            }
        }

        // 生成中途失败会抛出，走不到这里：进度不推进，下次连同新的轮次一起重试
        batches.forEach { batch ->
            batch.pending.consolidatedThrough?.let { advance(batch.conversation.id, it) }
        }

        val steps = messages.flatMap { it.getTools() }
        val touched = steps.mapNotNull { it.memoryWritePath() }
            .map { MemoryFile.normalizePath(it) ?: it }
            .distinct()
        val remaining = memoryRepository.getFiles(memoryId).map { it.path }.toSet()
        return MemoryConsolidationRun(
            memoryId = memoryId,
            finishedAt = System.currentTimeMillis(),
            conversationIds = batches.map { it.conversation.id }.toSet(),
            fullReview = fullReview,
            steps = steps,
            changedPaths = touched.filter { it in remaining },
            deletedPaths = touched.filterNot { it in remaining },
        )
    }

    private suspend fun loadPendingTurns(conversation: Conversation): PendingMemoryTurns {
        val messages = conversation.dialogueMessages()
        val consolidatedAt = consolidationDao.getConsolidatedAt(conversation.id.toString())
            // 第一次见到这个对话：定下从哪里开始整理并记下来，这之后的轮次一轮都不会被跳过
            ?: initialMemoryProgress(messages).also { advance(conversation.id, it) }
        val pending = pendingMemoryTurns(
            messages = messages,
            updatedAt = conversation.updateAt.toEpochMilli(),
            consolidatedAt = consolidatedAt,
        )
        // 没有新的轮次（比如只是重新生成了回复）也推进进度，免得每次扫描都把它读出来
        if (pending.turns.isEmpty()) pending.consolidatedThrough?.let { advance(conversation.id, it) }
        return pending
    }

    private suspend fun advance(conversationId: Uuid, consolidatedAt: Long) {
        runCatching {
            consolidationDao.upsert(MemoryConsolidationEntity(conversationId.toString(), consolidatedAt))
        }.onFailure {
            // 对话在整理期间被删掉时外键约束会拒绝写入
            Logging.log(TAG, "advance $conversationId: $it")
        }
    }
}

private class Batch(val conversation: Conversation, val pending: PendingMemoryTurns) {
    val input: MemoryConsolidationConversation by lazy {
        val messages = conversation.dialogueMessages().withIndex().filterNot { it.value.isContextCheckpoint }
        val firstPosition = pending.turns.first().position
        MemoryConsolidationConversation(
            id = conversation.id.toString(),
            title = conversation.title,
            recentContext = messages
                .filter { it.index < firstPosition }
                .takeLast(RECENT_CONTEXT_MESSAGES)
                .toMemoryTranscript(RECENT_CONTEXT_MESSAGE_LENGTH, RECENT_CONTEXT_MESSAGE_LENGTH),
            newTurns = pending.turns
                .flatMap { it.messages }
                .toMemoryTranscript(USER_MESSAGE_LENGTH, ASSISTANT_MESSAGE_LENGTH),
        )
    }
}

// 超出的对话留在待整理里，这次整理完后会接着处理；至少带上一个，否则永远轮不到
private fun List<Batch>.withinTranscriptBudget(): List<Batch> {
    var length = 0
    return take(MAX_CONVERSATIONS_PER_RUN).filterIndexed { index, batch ->
        length += batch.input.newTurns.length
        index == 0 || length <= MAX_TRANSCRIPT_LENGTH
    }
}

/** 助手开了记忆时它读写的记忆库 */
private fun Settings.memoryIdOf(conversation: Conversation): String? =
    getAssistantById(conversation.assistantId)?.takeIf { it.enableMemory }?.memoryId

// 整理要调用工具；快速模型不支持时退回对话模型
private fun Settings.consolidationModel(conversation: Conversation?, assistant: Assistant): Model? {
    val fastModel = findModelById(fastModelId)?.takeIf { ModelAbility.TOOL in it.abilities }
    val chatModel = conversation?.let { getChatModelOf(it) } ?: findModelById(assistant.chatModelId ?: chatModelId)
    return (fastModel ?: chatModel)?.copy(tools = emptySet())
}

/**
 * 依次检查 [candidates]，收集前 [limit] 个确实有待整理轮次的。
 *
 * 数量上限要在确认之后才算：候选是按更新时间粗筛的，重新生成过回复、最后一轮没结束的对话都在里面，
 * 先截断再确认的话，排在它们后面真正有新轮次的对话会一直轮不到。
 */
internal suspend fun <T> collectPending(
    candidates: List<T>,
    limit: Int,
    check: suspend (T) -> PendingMemoryConversation?,
): List<PendingMemoryConversation> {
    val found = mutableListOf<PendingMemoryConversation>()
    for (candidate in candidates) {
        if (found.size >= limit) break
        check(candidate)?.let(found::add)
    }
    return found
}

/** 一轮对话：一条用户消息和它之后直到下一条用户消息之前的回复，消息带着在对话里的位置 */
internal class MemoryTurn(val messages: List<IndexedValue<UIMessage>>) {
    val position: Int get() = messages.first().index
    val userMessage: UIMessage get() = messages.first().value

    // 回复还没出来，或者停在等待工具审批上
    val isFinished: Boolean
        get() = messages.last().value.let { last ->
            last.role == MessageRole.ASSISTANT && last.getTools().all { it.isExecuted }
        }
}

internal class PendingMemoryTurns(
    /** 这次要整理的轮次，积压多时只是最早的一批 */
    val turns: List<MemoryTurn>,
    /** 还没整理的总轮数，不小于 [turns] 的数量 */
    val totalTurns: Int,
    /** 整理完 [turns] 后进度应推进到的时间，没有可推进的内容时为 null */
    val consolidatedThrough: Long?,
)

// 按用户消息切成轮；压缩检查点是模型写的摘要，不是用户说的话
private fun memoryTurnsOf(messages: List<UIMessage>): List<MemoryTurn> {
    val indexed = messages.withIndex().filterNot { it.value.isContextCheckpoint }
    val starts = indexed.indices.filter { indexed[it].value.role == MessageRole.USER }
    return starts.mapIndexed { i, start ->
        MemoryTurn(indexed.subList(start, starts.getOrElse(i + 1) { indexed.size }))
    }
}

// 只发了图片之类没有文字的轮次没有可提取的内容
private val MemoryTurn.hasUserText: Boolean get() = userMessage.toText().isNotBlank()

/**
 * 第一次跟踪一个对话时的整理进度：只回看最近 [MAX_TURNS_PER_CONVERSATION] 轮。
 *
 * 助手后来才开启记忆，或者对话是导入的，都可能带着很长的历史，不该全部交给模型。
 * 只有这里会略过旧的轮次；进度定下来之后，它后面的内容都会被整理到。
 */
internal fun initialMemoryProgress(
    messages: List<UIMessage>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Long {
    val first = memoryTurnsOf(messages).filter { it.hasUserText }.takeLast(MAX_TURNS_PER_CONVERSATION).firstOrNull()
        ?: return 0
    return first.userMessage.createdAt.toInstant(timeZone).toEpochMilliseconds() - 1
}

/**
 * 从 [messages]（当前分支上用户和助手的消息）里找出 [consolidatedAt] 之后新增的轮次。
 *
 * 按用户消息的创建时间判断而不是按下标：重新生成不会改用户消息的时间，所以不会重复整理；
 * 编辑用户消息会产生一条新消息，会被当作新的一轮。
 */
internal fun pendingMemoryTurns(
    messages: List<UIMessage>,
    updatedAt: Long,
    consolidatedAt: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): PendingMemoryTurns {
    fun UIMessage.time() = createdAt.toInstant(timeZone).toEpochMilliseconds()

    val turns = memoryTurnsOf(messages)
    var pending = turns.filter { it.userMessage.time() > consolidatedAt }
    var consolidatedThrough = maxOf(updatedAt, turns.lastOrNull()?.messages?.last()?.value?.time() ?: 0)
    val last = turns.lastOrNull()
    if (last != null && !last.isFinished) {
        // 最后一轮还没结束：留到下次，进度停在它前面
        pending = pending.filter { it !== last }
        consolidatedThrough = last.userMessage.time() - 1
    }

    pending = pending.filter { it.hasUserText }
    val batch = pending.take(MAX_TURNS_PER_CONVERSATION)
    if (batch.isNotEmpty()) {
        val batchEnd = batch.last().userMessage.time()
        // 积压超过一次能处理的量（比如模型连续失败）时从最早的开始，进度只推进到这一批为止，
        // 剩下的留给下一次；如果直接推进到对话末尾，没交给模型的轮次就再也不会被整理了
        pending.drop(batch.size).firstOrNull { it.userMessage.time() > batchEnd }?.let { next ->
            consolidatedThrough = next.userMessage.time() - 1
        }
        // 交给模型的轮次一定要算整理过。时间相同的轮次靠进度分不开（导入的对话可能整段都是同一个时间），
        // 进度停在它们前面的话就推不动了，同一批会被一遍遍地整理
        consolidatedThrough = maxOf(consolidatedThrough, batchEnd)
    }

    return PendingMemoryTurns(
        turns = batch,
        totalTurns = pending.size,
        consolidatedThrough = consolidatedThrough.takeIf { it > consolidatedAt },
    )
}

/**
 * 转成给整理器看的文本，每条消息带上位置编号，方便它用 conversation_read 读被截断的部分。
 *
 * 主对话里按用户要求改过记忆的地方单独标一行，整理器要以用户的明确修改为准。
 */
internal fun List<IndexedValue<UIMessage>>.toMemoryTranscript(userLength: Int, assistantLength: Int): String =
    flatMap { (position, message) ->
        val maxLength = if (message.role == MessageRole.USER) userLength else assistantLength
        val text = message.toText().trim().let { if (it.length > maxLength) it.take(maxLength) + "..." else it }
        val memoryWrites = message.getTools().mapNotNull { tool ->
            tool.memoryWritePath()?.let { "[assistant updated memory: ${tool.toolName} $it]" }
        }
        listOfNotNull("[${message.role.name} #$position]: $text".takeIf { text.isNotEmpty() }) + memoryWrites
    }.joinToString("\n\n")
