package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation

/** 记录每次落库的内存实现；[beforeWrite] 可以让测试在写入前挂起 */
class FakeConversationStore(var persisted: Conversation? = null) : ConversationStore {
    val writes = mutableListOf<Pair<Conversation?, Conversation>>()
    var beforeWrite: suspend () -> Unit = {}

    override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }

    override suspend fun write(previous: Conversation?, current: Conversation) {
        beforeWrite()
        writes.add(previous to current)
        persisted = current
    }
}
