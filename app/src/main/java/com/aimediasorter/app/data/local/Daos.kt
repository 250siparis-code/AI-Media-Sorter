package com.aimediasorter.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY priority DESC, name ASC")
    fun observeAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY priority DESC, name ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE isEnabled = 1 ORDER BY priority DESC")
    suspend fun getEnabledCategories(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaultCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :categoryId AND isDefault = 0")
    suspend fun deleteCustomCategory(categoryId: String)
}

@Dao
interface MediaItemDao {
    @Query("SELECT * FROM media_items ORDER BY originalLastModifiedMs DESC")
    fun observeAllMediaItems(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items ORDER BY originalLastModifiedMs DESC")
    suspend fun getAllMediaItems(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    fun observeMediaItemById(id: Long): Flow<MediaItemEntity?>

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getMediaItemById(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE quickFingerprint = :fingerprint LIMIT 1")
    suspend fun findUnchangedByFingerprint(fingerprint: String): MediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaItem(item: MediaItemEntity): Long

    @Update
    suspend fun updateMediaItem(item: MediaItemEntity)

    @Query(
        "UPDATE media_items SET effectiveCategoryId = :categoryId, " +
        "effectiveCategoryName = :categoryName, isManuallyCorrected = 1, " +
        "needsManualReview = 0, proposedRelativePath = :proposedPath WHERE id = :itemId"
    )
    suspend fun updateManualCategory(
        itemId: Long,
        categoryId: String,
        categoryName: String,
        proposedPath: String
    )

    @Query("UPDATE media_items SET isExcludedFromTransfer = :excluded WHERE id = :itemId")
    suspend fun updateExcludedState(itemId: Long, excluded: Boolean)

    @Query("DELETE FROM media_items")
    suspend fun clearAllMediaItems()
}

@Dao
interface TransferLogDao {
    @Query("SELECT * FROM transfer_logs ORDER BY executedAtMs DESC")
    fun observeAllLogs(): Flow<List<TransferLogEntity>>

    @Query("SELECT * FROM transfer_logs ORDER BY executedAtMs DESC")
    suspend fun getAllLogs(): List<TransferLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: TransferLogEntity)
}

@Dao
interface AnalysisSessionDao {
    @Query("SELECT * FROM analysis_sessions ORDER BY startedAtMs DESC")
    fun observeSessions(): Flow<List<AnalysisSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: AnalysisSessionEntity)
}
