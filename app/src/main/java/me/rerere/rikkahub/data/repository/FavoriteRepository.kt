package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.sync.cloud.FavoriteDomainSync
import kotlin.uuid.Uuid

class FavoriteRepository(
    private val dao: FavoriteDAO,
) {
    // Set after Koin creates FavoriteDomainSync (avoids ctor cycles).
    var favoriteDomainSync: FavoriteDomainSync? = null

    fun listAll(): Flow<List<FavoriteEntity>> = dao.listAll()

    fun listByType(type: FavoriteType): Flow<List<FavoriteEntity>> = dao.listByType(type.value)

    suspend fun getByRefKey(refKey: String): FavoriteEntity? = dao.getByRefKey(refKey)

    suspend fun existsByRefKey(refKey: String): Boolean = dao.existsByRefKey(refKey)

    suspend fun deleteByRefKey(refKey: String): Int {
        val existing = dao.getByRefKey(refKey)
        val deleted = dao.deleteByRefKey(refKey)
        if (deleted > 0 && existing != null) {
            favoriteDomainSync?.enqueueDelete(existing.id)
        }
        return deleted
    }

    suspend fun deleteById(id: String): Int {
        val deleted = dao.deleteById(id)
        if (deleted > 0) {
            favoriteDomainSync?.enqueueDelete(id)
        }
        return deleted
    }

    suspend fun upsert(entity: FavoriteEntity) {
        dao.upsert(entity)
        favoriteDomainSync?.enqueueUpsert(entity)
    }

    suspend fun addNodeFavorite(target: NodeFavoriteTarget): FavoriteEntity {
        val refKey = NodeFavoriteAdapter.buildRefKey(target)
        val existing = dao.getByRefKey(refKey)
        val favorite = NodeFavoriteAdapter.buildFavoriteEntity(
            target = target,
            existing = existing,
        )
        dao.upsert(favorite)
        favoriteDomainSync?.enqueueUpsert(favorite)
        return favorite
    }

    suspend fun removeNodeFavorite(conversationId: Uuid, nodeId: Uuid): Int {
        return deleteByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }

    suspend fun isNodeFavorited(conversationId: Uuid, nodeId: Uuid): Boolean {
        return dao.existsByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }
}
