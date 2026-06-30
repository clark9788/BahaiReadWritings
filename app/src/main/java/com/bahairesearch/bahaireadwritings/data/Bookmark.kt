package com.bahairesearch.bahaireadwritings.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey val filename: String,
    val anchorId: String,
    val savedAt: Long
)
