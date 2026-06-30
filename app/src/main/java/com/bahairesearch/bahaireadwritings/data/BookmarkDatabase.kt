package com.bahairesearch.bahaireadwritings.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Bookmark::class], version = 1, exportSchema = false)
abstract class BookmarkDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile private var instance: BookmarkDatabase? = null

        fun getInstance(context: Context): BookmarkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookmarkDatabase::class.java,
                    "bookmarks.db"
                ).build().also { instance = it }
            }
    }
}
