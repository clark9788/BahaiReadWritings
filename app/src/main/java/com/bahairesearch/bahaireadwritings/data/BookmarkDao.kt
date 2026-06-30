package com.bahairesearch.bahaireadwritings.data

import androidx.room.*

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(bookmark: Bookmark)

    @Query("SELECT * FROM bookmarks WHERE filename = :filename")
    fun get(filename: String): Bookmark?

    @Query("DELETE FROM bookmarks WHERE filename = :filename")
    fun delete(filename: String)
}
