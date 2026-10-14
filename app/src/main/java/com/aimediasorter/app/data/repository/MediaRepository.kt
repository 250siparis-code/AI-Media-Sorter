package com.aimediasorter.app.data.repository

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import com.aimediasorter.app.data.local.AnalysisSessionEntity
import com.aimediasorter.app.data.local.AppDatabase
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.local.TransferLogEntity
import com.aimediasorter.app.data.model.OperationMode
import com.aimediasorter.app.data.model.TransferItemStatus
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.data.preferences.SettingsRepository
import com.aimediasorter.app.domain.ai.OnDeviceAiClassifier
import com.aimediasorter.app.domain.ai.VideoFrameAnalyzer
import com.aimediasorter.app.domain.ai.VisualFeatureExtractor
import com.aimediasorter.app.domain.storage.ExifAndTimestampManager
import com.aimediasorter.app.domain.storage.ExportBundleResult
import com.aimediasorter.app.domain.storage.ReportExporter
import com.aimediasorter.app.domain.storage.TermuxOperationExporter
import com.aimediasorter.app.data.model.TransferMethod
import com.aimediasorter.app.domain.storage.SafeFileMover
import com.aimediasorter.app.domain.storage.SingleTransferOutcome
import com.aimediasorter.app.domain.storage.StorageScanner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

data class LiveAnalysisProgress(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val totalFiles: Int = 0,
    val processedFiles: Int = 0,
    val currentFileName: String = "",
    val currentDetectedCategory: String = "",
    val currentPreviewUri: String = "",
    val skippedUnchangedCount: Int = 0,
    val errorMessage: String? = null
)

data class LiveTransferProgress(
    val isRunning: Boolean = false,
    val totalToTransfer: Int = 0,
    val processedCount: Int = 0,
    val succeededCount: Int = 0,
    val timestampVerifiedCount: Int = 0,
    val collisionResolvedCount: Int = 0,
    val failedCount: Int = 0,
    val currentFileName: String = "",
    val statusMessage: String = ""
)

class MediaRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    val settingsRepository = SettingsRepository(context)
    val timestampManager = ExifAndTimestampManager(context)
    val storageScanner = StorageScanner(context)
    val featureExtractor = VisualFeatureExtractor(context)
    val aiClassifier = OnDeviceAiClassifier(context, featureExtractor)
    val videoAnalyzer = VideoFrameAnalyzer(context, aiClassifier)
    val safeFileMover = SafeFileMover(context, timestampManager, storageScanner)
    val reportExporter = ReportExporter(context)
    val termuxExporter = TermuxOperationExporter(context)

    private val json = Json { encodeDefaults = true }

    private val _analysisProgress = MutableStateFlow(LiveAnalysisProgress())
    val analysisProgress: StateFlow<LiveAnalysisProgress> = _analysisProgress.asStateFlow()

    private val _transferProgress = MutableStateFlow(LiveTransferProgress())
    val transferProgress: StateFlow<LiveTransferProgress> = _transferProgress.asStateFlow()

    val categoriesFlow: Flow<List<CategoryEntity>> = db.categoryDao().observeAllCategories()
    val mediaItemsFlow: Flow<List<MediaItemEntity>> = db.mediaItemDao().observeAllMediaItems()
    val transferLogsFlow: Flow<List<TransferLogEntity>> = db.transferLogDao().observeAllLogs()
    val sessionsFlow: Flow<List<AnalysisSessionEntity>> = db.analysisSessionDao().observeSessions()

    suspend fun ensureDefaultCategories() {
        db.seedDefaultCategories()
    }

    suspend fun upsertCategory(category: CategoryEntity) {
        db.categoryDao().upsertCategory(category)
    }

    suspend fun deleteCustomCategory(categoryId: String) {
        db.categoryDao().deleteCustomCategory(categoryId)
    }

    suspend fun reassignMediaCategory(itemId: Long, newCategory: CategoryEntity) {
        val item = db.mediaItemDao().getMediaItemById(itemId) ?: return
        val proposedPath = "${newCategory.destinationSubfolder}/${item.fileName}"
        db.mediaItemDao().updateManualCategory(
            itemId = itemId,
            categoryId = newCategory.id,
            categoryName = newCategory.name,
            proposedPath = proposedPath
        )
    }

    suspend fun setExcludeFromTransfer(itemId: Long, excluded: Boolean) {
        db.mediaItemDao().updateExcludedState(itemId, excluded)
    }

    fun setAnalysisPaused(paused: Boolean) {
        _analysisProgress.value = _analysisProgress.value.copy(isPaused = paused)
    }

    /**
     * Executes full scanning and on-device visual analysis with checkpoint persistence,
     * unchanged-file detection, pause/resume support, and low-RAM pressure checks.
     */
    suspend fun executeMediaScanAndAnalysis(settings: AppSettings) {
        ensureDefaultCategories()
        val sessionId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        _analysisProgress.value = LiveAnalysisProgress(
            isRunning = true,
            isPaused = false,
            currentFileName = "Scanning source directory…"
        )

        try {
            val candidates = storageScanner.scanDirectory(
                sourceUriStr = settings.sourceUri,
                destinationUriStr = settings.destinationUri,
                includeSubfolders = settings.includeSubfolders,
                mediaTypeFilter = settings.mediaTypeFilter,
                enabledExtensions = settings.enabledExtensions
            ) { scannedSoFar ->
                _analysisProgress.value = _analysisProgress.value.copy(totalFiles = scannedSoFar)
            }

            val enabledCategories = db.categoryDao().getEnabledCategories()
            val total = candidates.size
            var processed = 0
            var skippedUnchanged = 0
            var uncertainCount = 0

            _analysisProgress.value = _analysisProgress.value.copy(
                totalFiles = total,
                processedFiles = 0
            )

            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()

                // Wait while paused
                while (_analysisProgress.value.isPaused) {
                    currentCoroutineContext().ensureActive()
                    delay(250L)
                }

                // Check if unchanged file already exists in Room database
                val existingUnchanged = db.mediaItemDao().findUnchangedByFingerprint(candidate.quickFingerprint)
                if (existingUnchanged != null) {
                    processed++
                    skippedUnchanged++
                    if (existingUnchanged.needsManualReview) uncertainCount++
                    _analysisProgress.value = _analysisProgress.value.copy(
                        processedFiles = processed,
                        skippedUnchangedCount = skippedUnchanged,
                        currentFileName = candidate.fileName,
                        currentDetectedCategory = existingUnchanged.effectiveCategoryName,
                        currentPreviewUri = candidate.uri.toString()
                    )
                    continue
                }

                // Memory guard on mid-range devices
                checkAndHandleLowMemory()

                val metadata = timestampManager.inspectMediaMetadata(
                    uri = candidate.uri,
                    filePath = candidate.filePath,
                    isVideo = candidate.isVideo,
                    fallbackLastModifiedMs = candidate.lastModifiedMs
                )

                val scoreMetrics = if (candidate.isVideo) {
                    videoAnalyzer.analyzeVideo(
                        uri = candidate.uri,
                        filePath = candidate.filePath,
                        durationMs = metadata.durationMs,
                        enabledCategories = enabledCategories,
                        aiEngineMode = settings.aiEngineMode,
                        customOnnxPath = settings.customOnnxModelPath,
                        enableHardwareAccel = settings.hardwareAcceleration,
                        confidenceThreshold = settings.confidenceThreshold
                    )
                } else {
                    aiClassifier.classifyImage(
                        uri = candidate.uri,
                        filePath = candidate.filePath,
                        orientationDegrees = metadata.orientationDegrees,
                        enabledCategories = enabledCategories,
                        aiEngineMode = settings.aiEngineMode,
                        customOnnxPath = settings.customOnnxModelPath,
                        enableHardwareAccel = settings.hardwareAcceleration,
                        confidenceThreshold = settings.confidenceThreshold
                    )
                }

                if (scoreMetrics.needsManualReview) {
                    uncertainCount++
                }

                val matchedCategory = enabledCategories.firstOrNull { it.id == scoreMetrics.categoryId }
                val subfolder = matchedCategory?.destinationSubfolder ?: scoreMetrics.categoryName
                val proposedRelativePath = "$subfolder/${candidate.fileName}"

                val entity = MediaItemEntity(
                    uriString = candidate.uri.toString(),
                    filePath = candidate.filePath,
                    fileName = candidate.fileName,
                    extension = candidate.extension,
                    isVideo = candidate.isVideo,
                    mimeType = candidate.mimeType,
                    fileSizeBytes = candidate.fileSizeBytes,
                    width = metadata.width,
                    height = metadata.height,
                    durationMs = metadata.durationMs,
                    originalLastModifiedMs = metadata.filesystemLastModifiedMs,
                    exifCaptureDateMs = metadata.exifCaptureDateMs,
                    quickFingerprint = candidate.quickFingerprint,
                    detectedCategoryId = scoreMetrics.categoryId,
                    detectedCategoryName = scoreMetrics.categoryName,
                    effectiveCategoryId = scoreMetrics.categoryId,
                    effectiveCategoryName = scoreMetrics.categoryName,
                    calibratedConfidence = scoreMetrics.calibratedConfidence,
                    rawSimilarityScore = scoreMetrics.rawSimilarityScore,
                    topMargin = scoreMetrics.topMargin,
                    secondaryCategoryName = scoreMetrics.secondaryCategoryName,
                    isAmbiguousMultiScene = scoreMetrics.isAmbiguousMultiScene,
                    needsManualReview = scoreMetrics.needsManualReview,
                    isManuallyCorrected = false,
                    isExcludedFromTransfer = false,
                    primarySubjectNote = scoreMetrics.primarySubjectNote,
                    frameBreakdownJson = json.encodeToString(scoreMetrics.frameSamples),
                    analyzedAtMs = System.currentTimeMillis(),
                    transferStatus = TransferItemStatus.PENDING.name,
                    proposedRelativePath = proposedRelativePath
                )

                db.mediaItemDao().upsertMediaItem(entity)
                processed++

                _analysisProgress.value = _analysisProgress.value.copy(
                    processedFiles = processed,
                    currentFileName = candidate.fileName,
                    currentDetectedCategory = scoreMetrics.categoryName,
                    currentPreviewUri = candidate.uri.toString()
                )
            }

            db.analysisSessionDao().upsertSession(
                AnalysisSessionEntity(
                    sessionId = sessionId,
                    startedAtMs = startedAt,
                    completedAtMs = System.currentTimeMillis(),
                    sourceUri = settings.sourceUri,
                    destinationUri = settings.destinationUri,
                    totalScanned = total,
                    totalClassified = processed,
                    uncertainCount = uncertainCount,
                    movedCount = 0,
                    failedCount = 0,
                    status = "COMPLETED"
                )
            )

            _analysisProgress.value = _analysisProgress.value.copy(isRunning = false, isPaused = false)
        } catch (e: Exception) {
            _analysisProgress.value = _analysisProgress.value.copy(
                isRunning = false,
                isPaused = false,
                errorMessage = e.message ?: "Analysis interrupted."
            )
            throw e
        }
    }

    /**
     * Executes verified file transfers for all non-excluded items in Move Mode.
     */
    suspend fun executeVerifiedTransfers(
        settings: AppSettings,
        includeReviewFlaggedItems: Boolean = false
    ): List<SingleTransferOutcome> {
        val sessionId = UUID.randomUUID().toString()
        val allItems = db.mediaItemDao().getAllMediaItems()
        val categoriesMap = db.categoryDao().getAllCategories().associateBy { it.id }

        val eligibleItems = allItems.filter { item ->
            !item.isExcludedFromTransfer &&
            item.transferStatus != TransferItemStatus.COMPLETED_VERIFIED.name &&
            (includeReviewFlaggedItems || !item.needsManualReview || item.isManuallyCorrected)
        }

        _transferProgress.value = LiveTransferProgress(
            isRunning = true,
            totalToTransfer = eligibleItems.size
        )

        val outcomes = mutableListOf<SingleTransferOutcome>()
        var processed = 0
        var succeeded = 0
        var tsVerified = 0
        var collisionsResolved = 0
        var failed = 0

        for (item in eligibleItems) {
            currentCoroutineContext().ensureActive()
            val cat = categoriesMap[item.effectiveCategoryId]
            val subfolder = cat?.destinationSubfolder ?: item.effectiveCategoryName
            val destRoot = cat?.customDestinationUri?.takeIf { it.isNotBlank() } ?: settings.destinationUri

            _transferProgress.value = _transferProgress.value.copy(
                currentFileName = item.fileName
            )

            val outcome = safeFileMover.transferMediaItem(
                sessionId = sessionId,
                item = item,
                sourceRootUri = settings.sourceUri,
                destinationRootUri = destRoot,
                categorySubfolder = subfolder,
                operationMode = OperationMode.MOVE_FILES,
                transferMethod = settings.transferMethod,
                collisionPolicy = settings.collisionPolicy,
                timestampPolicy = settings.timestampPolicy
            )

            outcomes.add(outcome)
            db.transferLogDao().insertLog(outcome.logEntry)

            val updatedItem = item.copy(
                transferStatus = outcome.status.name,
                destinationUriString = outcome.destinationUriString,
                sha256Checksum = outcome.sha256Checksum,
                timestampVerified = outcome.timestampPreserved,
                timestampStatusNote = outcome.detailsMessage
            )
            db.mediaItemDao().updateMediaItem(updatedItem)

            processed++
            when (outcome.status) {
                TransferItemStatus.COMPLETED_VERIFIED,
                TransferItemStatus.COMPLETED_TIMESTAMP_WARNING,
                TransferItemStatus.SKIPPED_COLLISION -> {
                    succeeded++
                    if (outcome.timestampPreserved) tsVerified++
                    if (outcome.collisionResolutionNote != null) collisionsResolved++
                }
                else -> failed++
            }

            _transferProgress.value = LiveTransferProgress(
                isRunning = processed < eligibleItems.size,
                totalToTransfer = eligibleItems.size,
                processedCount = processed,
                succeededCount = succeeded,
                timestampVerifiedCount = tsVerified,
                collisionResolvedCount = collisionsResolved,
                failedCount = failed,
                currentFileName = item.fileName,
                statusMessage = outcome.detailsMessage
            )
        }

        return outcomes
    }


    suspend fun exportTermuxCsv(selectedItemIds: Set<Long>, destRoot: String, method: TransferMethod): ExportBundleResult {
        val items = db.mediaItemDao().getAllMediaItems().filter { selectedItemIds.contains(it.id) && !it.isExcludedFromTransfer }
        val cats = db.categoryDao().getAllCategories().associateBy { it.id }
        val records = termuxExporter.buildExportRecords(items, cats, destRoot, method)
        val f = termuxExporter.exportClassificationCsv(records)
        return ExportBundleResult(f.parent ?: f.absolutePath, records.size, records.count { it.requiresSafAccessOnly }, listOf(f))
    }

    suspend fun exportTermuxJson(selectedItemIds: Set<Long>, destRoot: String, method: TransferMethod): ExportBundleResult {
        val items = db.mediaItemDao().getAllMediaItems().filter { selectedItemIds.contains(it.id) && !it.isExcludedFromTransfer }
        val cats = db.categoryDao().getAllCategories().associateBy { it.id }
        val records = termuxExporter.buildExportRecords(items, cats, destRoot, method)
        val f = termuxExporter.exportFileOperationsJson(records)
        return ExportBundleResult(f.parent ?: f.absolutePath, records.size, records.count { it.requiresSafAccessOnly }, listOf(f))
    }

    suspend fun exportTermuxScript(selectedItemIds: Set<Long>, destRoot: String, method: TransferMethod): ExportBundleResult {
        val items = db.mediaItemDao().getAllMediaItems().filter { selectedItemIds.contains(it.id) && !it.isExcludedFromTransfer }
        val cats = db.categoryDao().getAllCategories().associateBy { it.id }
        val records = termuxExporter.buildExportRecords(items, cats, destRoot, method)
        val f = termuxExporter.exportTermuxScript(records, method)
        return ExportBundleResult(f.parent ?: f.absolutePath, records.size, records.count { it.requiresSafAccessOnly }, listOf(f))
    }

    suspend fun exportTermuxAll(selectedItemIds: Set<Long>, destRoot: String, defaultMethod: TransferMethod): ExportBundleResult {
        val items = db.mediaItemDao().getAllMediaItems().filter { selectedItemIds.contains(it.id) && !it.isExcludedFromTransfer }
        val cats = db.categoryDao().getAllCategories().associateBy { it.id }
        return termuxExporter.exportAll(items, cats, destRoot, defaultMethod)
    }

    suspend fun exportCsv(): File {
        val items = db.mediaItemDao().getAllMediaItems()
        val logs = db.transferLogDao().getAllLogs()
        return reportExporter.exportCsvReport(items, logs)
    }

    suspend fun exportJson(): File {
        val items = db.mediaItemDao().getAllMediaItems()
        val logs = db.transferLogDao().getAllLogs()
        return reportExporter.exportJsonDiagnostic(items, logs)
    }

    private fun checkAndHandleLowMemory() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        if (memInfo.lowMemory) {
            System.gc()
        }
    }
}
