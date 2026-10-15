package com.aimediasorter.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
        AnalysisSessionEntity::class,
        TransferJournalEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun transferLogDao(): TransferLogDao
    abstract fun analysisSessionDao(): AnalysisSessionDao
    abstract fun transferJournalDao(): TransferJournalDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN sourceRootUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_items ADD COLUMN scanSessionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_items ADD COLUMN analysisSignature TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_items ADD COLUMN inferenceEngine TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE analysis_sessions ADD COLUMN analysisSignature TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS transfer_journal (operationId TEXT NOT NULL PRIMARY KEY, itemId INTEGER NOT NULL, sourceRootUri TEXT NOT NULL, sourceUri TEXT NOT NULL, requestedMethod TEXT NOT NULL, phase TEXT NOT NULL, destinationUri TEXT, stagingUri TEXT, checksum TEXT, originalModifiedMs INTEGER NOT NULL, destinationModifiedMs INTEGER NOT NULL, timestampPreserved INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, details TEXT NOT NULL)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_media_sorter.db"
                )
                    .addMigrations(MIGRATION_1_2)
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
