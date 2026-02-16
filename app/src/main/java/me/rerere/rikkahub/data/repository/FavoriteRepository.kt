package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import kotlin.uuid.Uuid

class FavoriteRepository(
    private val dao: FavoriteDAO,
) {
    fun listAll(): Flow<List<FavoriteEntity>> = dao.listAll()

    fun listByType(type: FavoriteType): Flow<List<FavoriteEntity>> = dao.listByType(type.value)

    suspend fun getByRefKey(refKey: String): FavoriteEntity? = dao.getByRefKey(refKey)

    suspend fun existsByRefKey(refKey: String): Boolean = dao.existsByRefKey(refKey)

    suspend fun deleteByRefKey(refKey: String): Int = dao.deleteByRefKey(refKey)

    suspend fun deleteById(id: String): Int = dao.deleteById(id)

    suspend fun addNodeFavorite(target: NodeFavoriteTarget): FavoriteEntity {
        val refKey = NodeFavoriteAdapter.buildRefKey(target)
        val existing = dao.getByRefKey(refKey)
        val favorite = NodeFavoriteAdapter.buildFavoriteEntity(
            target = target,
            existing = existing,
        )
        dao.upsert(favorite)
        return favorite
    }

    suspend fun removeNodeFavorite(nodeId: Uuid): Int {
        return dao.deleteByRefKey(NodeFavoriteAdapter.buildRefKey(nodeId.toString()))
    }

    suspend fun isNodeFavorited(nodeId: Uuid): Boolean {
        return dao.existsByRefKey(NodeFavoriteAdapter.buildRefKey(nodeId.toString()))
    }
}
