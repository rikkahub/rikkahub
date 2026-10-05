package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MediaCreationNodeEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationRecordEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationRecordWithVersion
import me.rerere.rikkahub.data.db.entity.MediaCreationSessionEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationSessionWithStats

@Dao
interface MediaCreationDAO {
    @Query(
        """
        SELECT s.*,
            (SELECT COUNT(*) FROM media_creation_node n WHERE n.session_id = s.id) AS node_count,
            (SELECT COUNT(*) FROM media_creation_record r
                WHERE r.session_id = s.id AND r.status IN (:activeStatuses)) AS active_count
        FROM media_creation_session s
        ORDER BY s.update_at DESC
        """
    )
    fun observeSessions(activeStatuses: List<String>): Flow<List<MediaCreationSessionWithStats>>

    @Query("SELECT * FROM media_creation_session WHERE id = :id")
    fun observeSession(id: String): Flow<MediaCreationSessionEntity?>

    @Query("SELECT * FROM media_creation_session WHERE id = :id")
    suspend fun getSession(id: String): MediaCreationSessionEntity?

    @Query("SELECT * FROM media_creation_session ORDER BY update_at DESC LIMIT 1")
    suspend fun getLatestSession(): MediaCreationSessionEntity?

    @Query(
        """
        SELECT s.* FROM media_creation_session s
        WHERE NOT EXISTS (SELECT 1 FROM media_creation_record r WHERE r.session_id = s.id)
        ORDER BY s.update_at DESC LIMIT 1
        """
    )
    suspend fun getEmptySession(): MediaCreationSessionEntity?

    @Insert
    suspend fun insertSession(session: MediaCreationSessionEntity)

    @Update
    suspend fun updateSession(session: MediaCreationSessionEntity)

    @Query("UPDATE media_creation_session SET title = :title WHERE id = :id")
    suspend fun renameSession(id: String, title: String)

    @Query("UPDATE media_creation_session SET draft = :draft WHERE id = :id")
    suspend fun updateDraft(id: String, draft: String)

    @Query("DELETE FROM media_creation_session WHERE id = :id")
    suspend fun deleteSession(id: String)

    /**
     * 会话的时间线：每一项取它选中的记录，最新的一项排在最前。同一项里的版本按创建顺序编号。
     */
    @Query(
        """
        SELECT r.*,
            (SELECT COUNT(*) FROM media_creation_record v
                WHERE v.node_id = n.id
                AND (v.create_at < r.create_at OR (v.create_at = r.create_at AND v.rowid < r.rowid))) AS version_index,
            (SELECT COUNT(*) FROM media_creation_record v WHERE v.node_id = n.id) AS version_count
        FROM media_creation_node n
        JOIN media_creation_record r ON r.id = n.selected_record_id
        WHERE n.session_id = :sessionId
        ORDER BY n.create_at DESC, n.rowid DESC
        """
    )
    fun nodePagingSource(sessionId: String): PagingSource<Int, MediaCreationRecordWithVersion>

    /** 一项在 [nodePagingSource] 里的位置，也就是同一个会话里排在它前面的项数；这一项不存在时返回 0。 */
    @Query(
        """
        SELECT COUNT(*) FROM media_creation_node n
        JOIN media_creation_node target ON target.id = :id AND n.session_id = target.session_id
        WHERE n.create_at > target.create_at OR (n.create_at = target.create_at AND n.rowid > target.rowid)
        """
    )
    suspend fun getNodePosition(id: String): Int

    @Query("SELECT * FROM media_creation_node WHERE id = :id")
    suspend fun getNode(id: String): MediaCreationNodeEntity?

    @Insert
    suspend fun insertNode(node: MediaCreationNodeEntity)

    @Query("UPDATE media_creation_node SET selected_record_id = :recordId WHERE id = :id")
    suspend fun selectRecord(id: String, recordId: String)

    @Query("DELETE FROM media_creation_node WHERE id = :id")
    suspend fun deleteNode(id: String)

    /** 一项的所有版本，按创建顺序。 */
    @Query("SELECT id FROM media_creation_record WHERE node_id = :nodeId ORDER BY create_at, rowid")
    suspend fun getVersionIds(nodeId: String): List<String>

    @Query("SELECT * FROM media_creation_record WHERE id = :id")
    suspend fun getRecord(id: String): MediaCreationRecordEntity?

    @Query("SELECT * FROM media_creation_record WHERE session_id = :sessionId")
    suspend fun getRecordsOfSession(sessionId: String): List<MediaCreationRecordEntity>

    @Query("SELECT * FROM media_creation_record WHERE status IN (:statuses)")
    suspend fun getRecordsByStatus(statuses: List<String>): List<MediaCreationRecordEntity>

    @Query("SELECT * FROM media_creation_record WHERE status = :status ORDER BY create_at DESC LIMIT :limit")
    fun observeRecentRecords(status: String, limit: Int): Flow<List<MediaCreationRecordEntity>>

    @Query("SELECT * FROM media_creation_record WHERE kind = :kind ORDER BY create_at DESC LIMIT 1")
    suspend fun getLatestRecordOfKind(kind: String): MediaCreationRecordEntity?

    @Insert
    suspend fun insertRecord(record: MediaCreationRecordEntity)

    @Update
    suspend fun updateRecord(record: MediaCreationRecordEntity)

    @Query("DELETE FROM media_creation_record WHERE id = :id")
    suspend fun deleteRecord(id: String)
}
