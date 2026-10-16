package com.aimediasorter.app.worker

import com.aimediasorter.app.data.diagnostics.Diagnostics
import com.aimediasorter.app.domain.diagnostics.*
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aimediasorter.app.AIMediaSorterApp
import com.aimediasorter.app.data.preferences.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class MediaAnalysisWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    companion object {
        const val WORK_NAME = "ai_media_sorter_analysis_work"
        const val SETTINGS_JSON = "settings_json"
    }
    override suspend fun doWork(): Result {
        val diagnostic=Diagnostics.operation(DiagnosticKind.WORKER,batchId=inputData.getString("cloud_batch_id"))
        return try {
            val snapshot = Json.decodeFromString<AppSettings>(
                inputData.getString(SETTINGS_JSON) ?: error("Missing confirmed settings snapshot."))
            setForeground(WorkerNotifications.create(applicationContext, false))
            (applicationContext as AIMediaSorterApp).repository.executeMediaScanAndAnalysis(snapshot, inputData.getString("cloud_batch_id").orEmpty(),
                inputData.getLongArray("selected_media_ids")?.toSet().orEmpty(),inputData.getBoolean("force_reanalysis",false))
            diagnostic.event(DiagnosticStage.COMPLETE,DiagnosticStatus.SUCCEEDED)
            Result.success()
        } catch (cancelled: CancellationException) {
            diagnostic.failure(cancelled)
            throw cancelled
        } catch (error: Exception) {
            diagnostic.failure(error)
            Result.failure()
        }
    }
}
