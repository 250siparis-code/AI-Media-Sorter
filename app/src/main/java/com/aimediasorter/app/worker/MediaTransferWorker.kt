package com.aimediasorter.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aimediasorter.app.AIMediaSorterApp
import com.aimediasorter.app.data.preferences.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class MediaTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    companion object {
        const val WORK_NAME = "ai_media_sorter_transfer_work"
        const val INCLUDE_REVIEW = "include_review"
    }
    override suspend fun doWork(): Result {
        return try {
            val snapshot = Json.decodeFromString<AppSettings>(
                inputData.getString(MediaAnalysisWorker.SETTINGS_JSON) ?: error("Missing confirmed settings snapshot."))
            setForeground(WorkerNotifications.create(applicationContext, true))
            (applicationContext as AIMediaSorterApp).repository.executePhysicalPlans(snapshot,
                inputData.getStringArray("physical_plan_ids")?.toList() ?: error("Missing explicitly approved physical plan."))
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
