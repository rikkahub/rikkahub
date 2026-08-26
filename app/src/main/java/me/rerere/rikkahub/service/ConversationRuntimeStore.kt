package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val RUNTIME_TAG = "ConversationRuntime"
private const val RUNTIME_IDLE_TIMEOUT_MS = 5_000L

class ConversationRuntimeStore(
    private val appScope: CoroutineScope,
    private val persistence: ConversationRuntimePersistence,
    private val fileCleaner: ConversationFileCleaner,
) {
    private class Entry(initial: Conversation) {
        val mutex = Mutex()
        val state = MutableStateFlow(ConversationRuntimeSnapshot(initial))
        val failures = MutableSharedFlow<ChatFailure>(extraBufferCapacity = 16)
        val references = AtomicInteger(0)

        var generationJob: Job? = null

        var generationJobId: Uuid? = null

        @Volatile
        var idleJob: Job? = null
    }

    private val entries = ConcurrentHashMap<Uuid, Entry>()
    private val _entriesVersion = MutableStateFlow(0L)
    val entriesVersion: Flow<Long> = _entriesVersion

    suspend fun ensure(
        conversationId: Uuid,
        initializer: suspend () -> Conversation,
    ): ConversationRuntimeSnapshot {
        entries[conversationId]?.let { return it.state.value }

        val initial = persistence.load(conversationId) ?: initializer()
        require(initial.id == conversationId) { "Initializer returned a different conversation id" }
        val created = Entry(initial)
        val entry = entries.putIfAbsent(conversationId, created) ?: created
        if (entry === created) {
            _entriesVersion.update { it + 1 }
            Log.i(RUNTIME_TAG, "Created runtime for $conversationId")
            scheduleRemovalIfIdle(conversationId, entry)
        }
        return entry.state.value
    }

    suspend fun require(conversationId: Uuid): ConversationRuntimeSnapshot {
        return ensure(conversationId) {
            throw ChatCommandException(
                ChatFailure(
                    code = ChatFailureCode.NotFound,
                    message = "Conversation not found",
                    conversationId = conversationId,
                )
            )
        }
    }

    suspend fun get(conversationId: Uuid): ConversationRuntimeSnapshot = require(conversationId)

    fun observe(conversationId: Uuid): Flow<ConversationRuntimeSnapshot> = flow {
        val entry = acquireEntry(conversationId)
        try {
            emitAll(entry.state)
        } finally {
            release(conversationId, entry)
        }
    }

    fun observeFailures(conversationId: Uuid): Flow<ChatFailure> = flow {
        val entry = acquireEntry(conversationId)
        try {
            emitAll(entry.failures.asSharedFlow())
        } finally {
            release(conversationId, entry)
        }
    }

    fun observeAll(): Flow<Map<Uuid, ConversationRuntimeSnapshot>> =
        _entriesVersion.flatMapLatest {
            val currentEntries = entries.entries.toList()
            if (currentEntries.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentEntries.map { (id, entry) ->
                    entry.state.map { id to it }
                }) { snapshots -> snapshots.toMap() }
            }
        }

    suspend fun beginGeneration(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
    ): GenerationHandle {
        return withEntry(conversationId) { entry ->
            var deletedFiles = emptyList<android.net.Uri>()
            val handle = entry.mutex.withLock {
                val current = entry.state.value
                if (current.generation.isBusy) {
                    throw conflict(conversationId, current.generation.generationIdOrNull)
                }
                val conversation = transform(current.conversation)
                require(conversation.id == conversationId) { "Conversation id cannot be changed" }
                persistence.persist(conversation)
                deletedFiles = current.conversation.files.filterNot(conversation.files::contains)
                val generationId = Uuid.random()
                val state = GenerationState.Queued(generationId)
                entry.state.value = current.copy(
                    conversation = conversation,
                    generation = state,
                    revision = current.revision + 1,
                )
                GenerationHandle(conversationId, generationId, state)
            }
            if (deletedFiles.isNotEmpty()) fileCleaner.delete(deletedFiles)
            handle
        }
    }

    suspend fun resumeGeneration(conversationId: Uuid, generationId: Uuid): GenerationHandle {
        return withLockedEntry(conversationId) { entry ->
            val current = entry.state.value
            val awaiting = current.generation as? GenerationState.AwaitingApproval
                ?: throw conflict(conversationId, generationId)
            if (awaiting.generationId != generationId) {
                throw conflict(conversationId, generationId)
            }
            persistence.persist(current.conversation)
            val state = GenerationState.Queued(generationId)
            entry.state.value = current.copy(
                generation = state,
                revision = current.revision + 1,
            )
            GenerationHandle(conversationId, generationId, state)
        }
    }

    suspend fun mutate(
        conversationId: Uuid,
        requireIdle: Boolean = false,
        persist: Boolean = false,
        transform: (Conversation) -> Conversation,
    ): ConversationRuntimeSnapshot {
        return withEntry(conversationId) { entry ->
            var deletedFiles = emptyList<android.net.Uri>()
            val snapshot = entry.mutex.withLock {
                val current = entry.state.value
                if (requireIdle && current.generation.isBusy) {
                    throw conflict(conversationId, current.generation.generationIdOrNull)
                }

                val nextConversation = transform(current.conversation)
                require(nextConversation.id == conversationId) { "Conversation id cannot be changed" }
                if (nextConversation == current.conversation) return@withLock current

                if (persist) {
                    persistence.persist(nextConversation)
                    deletedFiles = current.conversation.files.filterNot(nextConversation.files::contains)
                }

                current.copy(
                    conversation = nextConversation,
                    revision = current.revision + 1,
                ).also { entry.state.value = it }
            }
            if (deletedFiles.isNotEmpty()) {
                fileCleaner.delete(deletedFiles)
            }
            snapshot
        }
    }

    suspend fun persistCurrent(conversationId: Uuid): ConversationRuntimeSnapshot {
        return withLockedEntry(conversationId) { entry ->
            val current = entry.state.value
            persistence.persist(current.conversation)
            current
        }
    }

    suspend fun updateGeneration(
        conversationId: Uuid,
        expectedGenerationId: Uuid? = null,
        persistConversation: Boolean = false,
        transform: (GenerationState) -> GenerationState,
    ): ConversationRuntimeSnapshot {
        return withLockedEntry(conversationId) { entry ->
            val current = entry.state.value
            if (expectedGenerationId != null &&
                current.generation.generationIdOrNull != expectedGenerationId
            ) {
                throw conflict(conversationId, expectedGenerationId)
            }
            if (persistConversation) {
                persistence.persist(current.conversation)
            }
            current.copy(
                generation = transform(current.generation),
                revision = current.revision + 1,
            ).also { entry.state.value = it }
        }
    }

    suspend fun transitionGeneration(
        conversationId: Uuid,
        expectedGenerationId: Uuid,
        persistConversation: Boolean = false,
        allowed: (GenerationState) -> Boolean = { true },
        transformConversation: (Conversation) -> Conversation = { it },
        transformGeneration: (GenerationState, Conversation) -> GenerationState,
    ): ConversationRuntimeSnapshot {
        return withLockedEntry(conversationId) { entry ->
            val current = entry.state.value
            if (current.generation.generationIdOrNull != expectedGenerationId ||
                !allowed(current.generation)
            ) {
                throw conflict(conversationId, expectedGenerationId)
            }
            val conversation = transformConversation(current.conversation)
            require(conversation.id == conversationId) { "Conversation id cannot be changed" }
            if (persistConversation) {
                persistence.persist(conversation)
            }
            current.copy(
                conversation = conversation,
                generation = transformGeneration(current.generation, conversation),
                revision = current.revision + 1,
            ).also { entry.state.value = it }
        }
    }

    suspend fun registerJob(
        conversationId: Uuid,
        generationId: Uuid,
        job: Job,
    ) {
        withLockedEntry(conversationId) { entry ->
            val current = entry.state.value
            if (current.generation.generationIdOrNull != generationId) {
                throw conflict(conversationId, generationId)
            }
            synchronized(entry) {
                val previous = entry.generationJob
                check(
                    previous == null || previous.isCompleted || entry.generationJobId != generationId
                ) { "Generation job already registered" }
                entry.generationJob = job
                entry.generationJobId = generationId
            }
            job.invokeOnCompletion {
                synchronized(entry) {
                    if (entry.generationJob === job) {
                        entry.generationJob = null
                        entry.generationJobId = null
                    }
                }
                scheduleRemovalIfIdle(conversationId, entry)
            }
        }
    }

    suspend fun cancelJob(conversationId: Uuid, generationId: Uuid): Job? {
        return withLockedEntry(conversationId) { entry ->
            val current = entry.state.value
            if (current.generation.generationIdOrNull != generationId) {
                throw conflict(conversationId, generationId)
            }
            synchronized(entry) {
                entry.generationJob.takeIf { entry.generationJobId == generationId }
            }?.also { it.cancel() }
        }
    }

    suspend fun awaitJobCompletion(conversationId: Uuid, generationId: Uuid) {
        val job = withEntry(conversationId) { entry ->
            entry.mutex.withLock {
                if (entry.state.value.generation.generationIdOrNull != generationId) {
                    throw conflict(conversationId, generationId)
                }
                synchronized(entry) {
                    entry.generationJob.takeIf { entry.generationJobId == generationId }
                }
            }
        }
        job?.join()
    }

    suspend fun emitFailure(conversationId: Uuid, failure: ChatFailure) {
        withEntry(conversationId) { it.failures.emit(failure) }
    }

    suspend fun delete(conversationId: Uuid) {
        val entry = acquireEntry(conversationId)
        var removed = false
        try {
            entry.mutex.withLock {
                val current = entry.state.value
                if (current.generation.isBusy) {
                    throw conflict(conversationId, current.generation.generationIdOrNull)
                }
                persistence.delete(current.conversation)
            }
            removed = synchronized(entries) { entries.remove(conversationId, entry) }
            if (removed) {
                cleanupEntry(entry)
                _entriesVersion.update { it + 1 }
            }
        } finally {
            release(conversationId, entry, scheduleRemoval = !removed)
        }
    }

    fun currentSnapshots(): Map<Uuid, ConversationRuntimeSnapshot> =
        entries.mapValues { it.value.state.value }

    fun cleanup() {
        entries.values.forEach(::cleanupEntry)
        entries.clear()
        _entriesVersion.update { it + 1 }
    }

    private suspend fun acquireEntry(conversationId: Uuid): Entry {
        while (true) {
            synchronized(entries) {
                entries[conversationId]?.let { entry ->
                    synchronized(entry) {
                        entry.idleJob?.cancel()
                        entry.idleJob = null
                    }
                    entry.references.incrementAndGet()
                    Log.d(RUNTIME_TAG, "Acquire $conversationId refs=${entry.references.get()}")
                    return entry
                }
            }
            require(conversationId)
        }
    }

    private suspend fun <T> withEntry(conversationId: Uuid, block: suspend (Entry) -> T): T {
        val entry = acquireEntry(conversationId)
        return try {
            block(entry)
        } finally {
            release(conversationId, entry)
        }
    }

    private suspend fun <T> withLockedEntry(
        conversationId: Uuid,
        block: suspend (Entry) -> T,
    ): T = withEntry(conversationId) { entry ->
        entry.mutex.withLock { block(entry) }
    }

    private fun release(
        conversationId: Uuid,
        entry: Entry,
        scheduleRemoval: Boolean = true,
    ) {
        val refs = entry.references.decrementAndGet().coerceAtLeast(0)
        if (scheduleRemoval && refs == 0) scheduleRemovalIfIdle(conversationId, entry)
    }

    private fun scheduleRemovalIfIdle(conversationId: Uuid, entry: Entry) {
        if (entry.references.get() > 0 || entry.state.value.generation.isBusy) return
        val removalJob = appScope.launch {
            delay(RUNTIME_IDLE_TIMEOUT_MS)
            val removed = entry.mutex.withLock {
                synchronized(entries) {
                    if (entry.references.get() <= 0 &&
                        !entry.state.value.generation.isBusy &&
                        entries[conversationId] === entry
                    ) {
                        entries.remove(conversationId, entry)
                    } else {
                        false
                    }
                }
            }
            if (removed) {
                cleanupEntry(entry)
                _entriesVersion.update { it + 1 }
                Log.i(RUNTIME_TAG, "Removed idle runtime $conversationId")
            }
        }
        synchronized(entry) {
            entry.idleJob?.cancel()
            entry.idleJob = removalJob
        }
    }

    private fun cleanupEntry(entry: Entry) {
        synchronized(entry) {
            entry.generationJob?.cancel()
            entry.generationJob = null
            entry.generationJobId = null
        }
        synchronized(entry) {
            entry.idleJob?.cancel()
            entry.idleJob = null
        }
    }

    private fun conflict(conversationId: Uuid, generationId: Uuid?) = ChatCommandException(
        ChatFailure(
            code = ChatFailureCode.Conflict,
            message = "Conversation already has an active generation",
            conversationId = conversationId,
            generationId = generationId,
        )
    )
}
