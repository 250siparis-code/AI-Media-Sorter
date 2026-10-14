package com.aimediasorter.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aimediasorter.app.data.model.DefaultCategories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        CategoryEntity::class,
        MediaItemEntity::class,
        TransferLogEntity::class,
        AnalysisSessionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun transferLogDao(): TransferLogDao
    abstract fun analysisSessionDao(): AnalysisSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_media_sorter.db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                getInstance(context).seedDefaultCategories()
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    suspend fun seedDefaultCategories() {
        val existing = categoryDao().getAllCategories()
        if (existing.isEmpty()) {
            val defaults = DefaultCategories.ALL.map { spec ->
                CategoryEntity(
                    id = spec.id,
                    name = spec.defaultName,
                    description = spec.defaultDescription,
                    colorHex = spec.colorHex,
                    iconKey = spec.iconKey,
                    priority = spec.priority,
                    isEnabled = true,
                    isDefault = true,
                    destinationSubfolder = spec.subfolderName,
                    customDestinationUri = null
                )
            }
            categoryDao().insertDefaultCategories(defaults)
        }
    }
}
