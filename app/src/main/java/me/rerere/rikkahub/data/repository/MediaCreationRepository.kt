package me.rerere.rikkahub.data.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.mediagen.model.MediaKind
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MediaCreationDAO
import me.rerere.rikkahub.data.db.entity.MediaCreationNodeEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationRecordEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationSessionEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationNode
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationSession
import me.rerere.rikkahub.data.model.MediaCreationStatus
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 媒体创作的会话、生成记录以及它们在磁盘上的文件。
 *
 * 时间线由「项」组成，每一项下面是一条或多条记录（版本），其中一条被选中显示。
 *
 * 文件按归属存放：`media_creation/{会话}/{记录}/` 下是一条记录自己的输入和输出，`media_creation/{会话}/draft/`
 * 下是草稿里还没有提交的素材，从相册选进来的和选用已有产出时复制过来的都在这里。删除记录或会话时连同目录一起删除。
 */
class MediaCreationRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val dao: MediaCreationDAO,
    private val json: Json,
) {
    private val activeStatuses = MediaCreationStatus.ACTIVE.map { it.name }

    // ---- 文件 ----

    fun resolve(path: String): File = File(context.filesDir, path)

    fun relativePath(file: File): String = file.relativeTo(context.filesDir).path

    fun sessionDir(sessionId: Uuid): File = File(context.filesDir, "${FileFolders.MEDIA_CREATION}/$sessionId")

    fun recordDir(sessionId: Uuid, recordId: Uuid): File = File(sessionDir(sessionId), recordId.toString())

    fun draftDir(sessionId: Uuid): File = File(sessionDir(sessionId), DRAFT_DIR)

    // ---- 会话 ----

    fun observeSessions(): Flow<List<MediaCreationSession>> =
        dao.observeSessions(activeStatuses).map { sessions ->
            sessions.map {
                it.session.toSession().copy(nodeCount = it.nodeCount, activeCount = it.activeCount)
            }
        }

    fun observeSession(id: Uuid): Flow<MediaCreationSession?> =
        dao.observeSession(id.toString()).map { it?.toSession() }

    suspend fun getSession(id: Uuid): MediaCreationSession? = dao.getSession(id.toString())?.toSession()

    suspend fun getLatestSession(): MediaCreationSession? = dao.getLatestSession()?.toSession()

    /**
     * 返回一个还没有任何记录的会话：已经有空会话时直接复用，避免攒下一堆空会话。
     */
    suspend fun getOrCreateEmptySession(draft: MediaCreationDraft): MediaCreationSession = database.withTransaction {
        dao.getEmptySession()?.toSession() ?: MediaCreationSession(draft = draft).also {
            dao.insertSession(it.toEntity())
        }
    }

    /** 准备一个空会话，模型沿用最近那个会话选中的。 */
    suspend fun newSession(): MediaCreationSession =
        getOrCreateEmptySession(MediaCreationDraft(modelId = getLatestSession()?.draft?.modelId))

    suspend fun renameSession(id: Uuid, title: String) = dao.renameSession(id.toString(), title)

    suspend fun saveDraft(id: Uuid, draft: MediaCreationDraft) =
        dao.updateDraft(id.toString(), json.encodeToString(draft))

    suspend fun updateSession(id: Uuid, transform: (MediaCreationSession) -> MediaCreationSession) {
        database.withTransaction {
            val session = dao.getSession(id.toString())?.toSession() ?: return@withTransaction
            dao.updateSession(transform(session).toEntity())
        }
    }

    suspend fun deleteSession(id: Uuid) {
        dao.deleteSession(id.toString())
        withContext(Dispatchers.IO) { sessionDir(id).deleteRecursively() }
    }

    // ---- 时间线 ----

    /**
     * 会话的时间线，最新的一项排在最前。开启占位后第 0 项始终是最新的那一项，界面可以可靠地滚回底部。
     */
    fun observeNodes(sessionId: Uuid): Flow<PagingData<MediaCreationNode>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = true)) {
            dao.nodePagingSource(sessionId.toString())
        }.flow.map { page ->
            page.map { MediaCreationNode(it.record.toRecord(), it.versionIndex, it.versionCount) }
        }

    /** 一项在 [observeNodes] 里的位置。 */
    suspend fun getNodePosition(id: Uuid): Int = dao.getNodePosition(id.toString())

    suspend fun hasNode(id: Uuid): Boolean = dao.getNode(id.toString()) != null

    /**
     * 让一项改为显示相邻的版本，[offset] 为 -1 是上一个、1 是下一个；已经到头时不变。
     */
    suspend fun switchVersion(nodeId: Uuid, offset: Int) = database.withTransaction {
        val node = dao.getNode(nodeId.toString()) ?: return@withTransaction
        val versions = dao.getVersionIds(node.id)
        val target = versions.getOrNull(versions.indexOf(node.selectedRecordId) + offset) ?: return@withTransaction
        dao.selectRecord(node.id, target)
    }

    // ---- 记录 ----

    suspend fun getRecord(id: Uuid): MediaCreationRecord? = dao.getRecord(id.toString())?.toRecord()

    suspend fun getRecordsOfSession(sessionId: Uuid): List<MediaCreationRecord> =
        dao.getRecordsOfSession(sessionId.toString()).map { it.toRecord() }

    suspend fun getActiveRecords(): List<MediaCreationRecord> =
        dao.getRecordsByStatus(activeStatuses).map { it.toRecord() }

    fun observeRecentSucceededRecords(limit: Int): Flow<List<MediaCreationRecord>> =
        dao.observeRecentRecords(MediaCreationStatus.SUCCEEDED.name, limit).map { records ->
            records.map { it.toRecord() }
        }

    suspend fun getLatestRecordOfKind(kind: MediaKind): MediaCreationRecord? =
        dao.getLatestRecordOfKind(kind.name)?.toRecord()

    /**
     * 把 [record] 写进时间线并选中它：[MediaCreationRecord.nodeId] 对应的项已经存在时成为它的新版本，
     * 否则在时间线末尾另起一项。
     */
    suspend fun insertRecord(record: MediaCreationRecord) = database.withTransaction {
        val entity = record.toEntity()
        if (dao.getNode(entity.nodeId) == null) {
            dao.insertNode(
                MediaCreationNodeEntity(
                    id = entity.nodeId,
                    sessionId = entity.sessionId,
                    selectedRecordId = entity.id,
                    createAt = entity.createAt,
                )
            )
            dao.insertRecord(entity)
        } else {
            dao.insertRecord(entity)
            dao.selectRecord(entity.nodeId, entity.id)
        }
    }

    /**
     * 原子地修改一条记录，返回修改后的记录；记录已被删除时返回 null。
     */
    suspend fun updateRecord(
        id: Uuid,
        transform: (MediaCreationRecord) -> MediaCreationRecord,
    ): MediaCreationRecord? = database.withTransaction {
        val current = dao.getRecord(id.toString())?.toRecord() ?: return@withTransaction null
        val updated = transform(current)
        if (updated != current) dao.updateRecord(updated.copy(updateAt = Instant.now()).toEntity())
        updated
    }

    /**
     * 删除一个版本。它是所在项的最后一个版本时这一项也随之消失；删掉的是正在显示的版本时，
     * 改为显示它前面的那个，没有的话显示后面的。
     */
    suspend fun deleteRecord(record: MediaCreationRecord) {
        database.withTransaction {
            val id = record.id.toString()
            val nodeId = record.nodeId.toString()
            val versions = dao.getVersionIds(nodeId)
            dao.deleteRecord(id)
            val node = dao.getNode(nodeId) ?: return@withTransaction
            val remaining = versions - id
            when {
                remaining.isEmpty() -> dao.deleteNode(nodeId)
                node.selectedRecordId == id ->
                    dao.selectRecord(nodeId, remaining[(versions.indexOf(id) - 1).coerceIn(remaining.indices)])
            }
        }
        withContext(Dispatchers.IO) { recordDir(record.sessionId, record.id).deleteRecursively() }
    }

    // ---- 映射 ----

    private fun MediaCreationSessionEntity.toSession() = MediaCreationSession(
        id = Uuid.parse(id),
        title = title,
        draft = decodeOrDefault(draft, MediaCreationDraft()),
        createAt = Instant.ofEpochMilli(createAt),
        updateAt = Instant.ofEpochMilli(updateAt),
    )

    private fun MediaCreationSession.toEntity() = MediaCreationSessionEntity(
        id = id.toString(),
        title = title,
        draft = json.encodeToString(draft),
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
    )

    private fun MediaCreationRecordEntity.toRecord() = MediaCreationRecord(
        id = Uuid.parse(id),
        sessionId = Uuid.parse(sessionId),
        nodeId = Uuid.parse(nodeId),
        providerId = Uuid.parse(providerId),
        providerName = providerName,
        modelId = modelId,
        kind = MediaKind.valueOf(kind),
        prompt = prompt,
        params = decodeOrDefault(params, MediaCreationParams()),
        inputs = decodeOrDefault(inputs, emptyList()),
        status = MediaCreationStatus.valueOf(status),
        taskId = taskId,
        error = error,
        outputs = decodeOrDefault(outputs, emptyList()),
        createAt = Instant.ofEpochMilli(createAt),
        updateAt = Instant.ofEpochMilli(updateAt),
    )

    private fun MediaCreationRecord.toEntity() = MediaCreationRecordEntity(
        id = id.toString(),
        sessionId = sessionId.toString(),
        nodeId = nodeId.toString(),
        providerId = providerId.toString(),
        providerName = providerName,
        modelId = modelId,
        kind = kind.name,
        prompt = prompt,
        params = json.encodeToString(params),
        inputs = json.encodeToString(inputs),
        status = status.name,
        taskId = taskId,
        error = error,
        outputs = json.encodeToString(outputs),
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
    )

    private inline fun <reified T> decodeOrDefault(text: String, default: T): T =
        runCatching { json.decodeFromString<T>(text) }.getOrDefault(default)

    companion object {
        private const val DRAFT_DIR = "draft"
        private const val PAGE_SIZE = 20
    }
}
