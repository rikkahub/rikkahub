package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
