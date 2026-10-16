package com.aimediasorter.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY priority DESC, name ASC")
    fun observeAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY priority DESC, name ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE isEnabled = 1 ORDER BY priority DESC")
    suspend fun getEnabledCategories(): List<CategoryEntity>

    @Upsert
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

    @Query("SELECT * FROM media_items WHERE sourceRootUri = :source AND scanSessionId = (SELECT sessionId FROM analysis_sessions WHERE sourceUri = :source ORDER BY rowid DESC LIMIT 1) ORDER BY originalLastModifiedMs DESC")
    fun observeCurrentMediaItems(source: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE sourceRootUri = :source AND scanSessionId = (SELECT sessionId FROM analysis_sessions WHERE sourceUri = :source ORDER BY rowid DESC LIMIT 1) ORDER BY originalLastModifiedMs DESC")
    suspend fun getCurrentMediaItems(source: String): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaItem(item: MediaItemEntity): Long

    @Update
    suspend fun updateMediaItem(item: MediaItemEntity)

    @Query(
        "UPDATE media_items SET effectiveCategoryId = :categoryId, " +
        "effectiveCategoryName = :categoryName, isManuallyCorrected = 1, " +
        "needsManualReview = CASE WHEN sha256Checksum IS NULL THEN 1 ELSE 0 END, transferStatus = 'PENDING', proposedRelativePath = :proposedPath WHERE id = :itemId"
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

    @Query("SELECT * FROM analysis_sessions WHERE sourceUri = :source ORDER BY rowid DESC LIMIT 1")
    suspend fun latestForSource(source: String): AnalysisSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: AnalysisSessionEntity)
}

@Dao
interface TransferJournalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TransferJournalEntity)

    @Query("SELECT * FROM transfer_journal WHERE phase NOT IN ('FINALIZED', 'RECOVERED', 'FAILED', 'CANCELLED')")
    suspend fun unfinished(): List<TransferJournalEntity>
}

@Dao
interface VirtualGalleryDao {
    @Query("SELECT l.* FROM media_category_links l INNER JOIN media_items m ON m.id=l.mediaId WHERE m.sourceRootUri=:source")
    fun observeLinks(source: String): Flow<List<MediaCategoryLink>>
    @Query("SELECT DISTINCT mediaId FROM media_category_links WHERE categoryId=:category AND origin='EFFECTIVE'")
    suspend fun affectedMedia(category: String): List<Long>
    @Query("SELECT * FROM media_category_links WHERE mediaId=:id")
    suspend fun links(id: Long): List<MediaCategoryLink>
    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<MediaCategoryLink>)
    @Query("DELETE FROM media_category_links WHERE mediaId=:id AND origin=:origin")
    suspend fun clearLinks(id: Long,origin: String)
    @Query("SELECT * FROM physical_plans ORDER BY groupId,sequence")
    fun observePlans(): Flow<List<PhysicalPlanEntity>>
    @Query("SELECT * FROM physical_plans WHERE id IN (:ids) ORDER BY groupId,sequence")
    suspend fun plans(ids: List<String>): List<PhysicalPlanEntity>
    @Query("SELECT * FROM physical_plans WHERE mediaId=:id")
    suspend fun plansForMedia(id: Long): List<PhysicalPlanEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT)
    suspend fun insertPlans(plans: List<PhysicalPlanEntity>)
    @Update
    suspend fun updatePlan(plan: PhysicalPlanEntity)
    @Query("UPDATE physical_plans SET status='CANCELLED',note='Cancelled before this operation started.' WHERE status='APPROVED'")
    suspend fun cancelUnstartedPlans()
    @Query("UPDATE physical_plans SET status='CANCEL_REQUESTED',note='Cancellation requested; inspect source and destination.' WHERE status='RUNNING'")
    suspend fun requestCancelRunningPlans()
    @Query("UPDATE physical_plans SET status='RECOVERY_REQUIRED',note='Interrupted operation. Inspect verified destinations before retrying; source deletion was not resumed.' WHERE status IN ('RUNNING','CANCEL_REQUESTED')")
    suspend fun recoverRunningPlans()
}
