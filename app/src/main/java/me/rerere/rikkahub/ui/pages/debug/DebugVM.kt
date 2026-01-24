package me.rerere.rikkahub.ui.pages.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.random.Random
import kotlin.uuid.Uuid

class DebugVM(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * 创建一个超大的对话用于测试 CursorWindow 限制
     * @param sizeMB 目标大小（MB）
     */
    fun createOversizedConversation(sizeMB: Int = 3) {
        viewModelScope.launch {
            val targetSize = sizeMB * 1024 * 1024
            val messageNodes = mutableListOf<MessageNode>()
            var currentSize = 0

            // 生成大量消息直到达到目标大小
            var index = 0
            while (currentSize < targetSize) {
                // 生成一个包含大量文本的消息（约 100KB 每条）
                val largeText = buildString {
                    repeat(100) {
                        append("这是一段很长的测试文本，用于测试 CursorWindow 的大小限制。")
                        append("Row too big to fit into CursorWindow 错误通常发生在单行数据超过 2MB 时。")
                        append("Lorem ipsum dolor sit amet, consectetur adipiscing elit. ")
                        append("Index: $index, Block: $it. ")
                    }
                }

                val userMessage = UIMessage(
                    id = Uuid.random(),
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(largeText)),
                    createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                )
                val assistantMessage = UIMessage(
                    id = Uuid.random(),
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("回复: $largeText")),
                    createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                )

                messageNodes.add(MessageNode.of(userMessage))
                messageNodes.add(MessageNode.of(assistantMessage))

                currentSize += largeText.length * 2 * 2 // 大约估算
                index++
            }

            val conversation = Conversation(
                id = Uuid.random(),
                assistantId = DEFAULT_ASSISTANT_ID,
                title = "超大对话测试 (${sizeMB}MB)",
                messageNodes = messageNodes,
            )

            conversationRepository.insertConversation(conversation)
        }
    }

    fun createConversationWithMessages(messageCount: Int = 1024) {
        viewModelScope.launch {
            val messageNodes = ArrayList<MessageNode>(messageCount)
            val timeZone = TimeZone.currentSystemDefault()
            repeat(messageCount) { index ->
                val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
                val message = UIMessage(
                    id = Uuid.random(),
                    role = role,
                    parts = listOf(UIMessagePart.Text(randomMessageText(index, role))),
                    createdAt = Clock.System.now().toLocalDateTime(timeZone),
                )
                messageNodes.add(MessageNode.of(message))
            }

            val conversation = Conversation(
                id = Uuid.random(),
                assistantId = DEFAULT_ASSISTANT_ID,
                title = "${messageCount}条消息测试",
                messageNodes = messageNodes,
            )

            conversationRepository.insertConversation(conversation)
        }
    }

    private fun randomMessageText(index: Int, role: MessageRole): String {
        val fragments = listOf(
            "快速", "随机", "消息", "样例", "用于", "测试", "列表", "渲染", "滚动", "性能",
            "聊天", "对话", "内容", "结构", "验证", "分页", "顺序", "稳定", "系统",
        )
        val wordCount = Random.nextInt(6, 14)
        val prefix = if (role == MessageRole.USER) "用户" else "助手"
        val body = List(wordCount) { fragments.random() }.joinToString(" ")
        return "$prefix#${index + 1}: $body"
    }
}
