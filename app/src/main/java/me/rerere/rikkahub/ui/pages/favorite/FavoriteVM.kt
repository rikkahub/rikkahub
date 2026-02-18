package me.rerere.rikkahub.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkahub.data.model.FavoriteType
import me.rerere.rikkahub.data.repository.FavoriteRepository
import kotlin.uuid.Uuid

data class NodeFavoriteListItem(
    val id: String,
    val refKey: String,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val conversationTitle: String,
    val preview: String,
    val createdAt: Long,
)

class FavoriteVM(
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    val nodeFavorites = favoriteRepository
        .listByType(FavoriteType.NODE)
        .map { favorites ->
            favorites.mapNotNull { entity ->
                val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return@mapNotNull null
                val meta = NodeFavoriteAdapter.decodeMeta(entity)

                NodeFavoriteListItem(
                    id = entity.id,
                    refKey = entity.refKey,
                    conversationId = ref.conversationId,
                    nodeId = ref.nodeId,
                    conversationTitle = meta?.title.orEmpty(),
                    preview = meta?.previewText ?: "",
                    createdAt = entity.createdAt,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun removeFavorite(refKey: String) {
        viewModelScope.launch {
            favoriteRepository.deleteByRefKey(refKey)
        }
    }

    suspend fun getEntityByRefKey(refKey: String): FavoriteEntity? {
        return favoriteRepository.getByRefKey(refKey)
    }

    fun restoreFavorite(entity: FavoriteEntity) {
        viewModelScope.launch {
            favoriteRepository.upsert(entity)
        }
    }
}
