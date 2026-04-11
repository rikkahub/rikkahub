package com.hefengfan.hffchat.data.favorite

import com.hefengfan.hffchat.data.db.entity.FavoriteEntity
import com.hefengfan.hffchat.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
