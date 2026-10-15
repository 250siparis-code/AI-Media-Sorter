package com.aimediasorter.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.aimediasorter.app.data.local.*
import com.aimediasorter.app.data.model.*
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.data.preferences.SettingsRepository
import com.aimediasorter.app.domain.ai.*
import com.aimediasorter.app.domain.cloud.*
import com.aimediasorter.app.domain.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class LiveAnalysisProgress(
    val isRunning: Boolean = false, val isPaused: Boolean = false,
    val totalFiles: Int = 0, val processedFiles: Int = 0,
    val currentFileName: String = "", val currentDetectedCategory: String = "",
    val currentPreviewUri: String = "", val skippedUnchangedCount: Int = 0,
    val errorMessage: String? = null, val isDiscovering: Boolean = false
)
data class LiveTransferProgress(
    val isRunning: Boolean = false, val totalToTransfer: Int = 0,
    val processedCount: Int = 0, val succeededCount: Int = 0,
    val timestampVerifiedCount: Int = 0, val collisionResolvedCount: Int = 0,
    val failedCount: Int = 0, val currentFileName: String = "",
    val statusMessage: String = "", val sourceRetainedCount: Int = 0,
    val skippedCount: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepository(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val operationMutex = Mutex()
    val settingsRepository = SettingsRepository(context)
    val timestampManager = ExifAndTimestampManager(context)
    val storageScanner = StorageScanner(context)
    val featureExtractor = VisualFeatureExtractor(context)
    val aiClassifier = OnDeviceAiClassifier(context, featureExtractor)
    val cloud = CloudAnalysisService(context, featureExtractor)
    val videoAnalyzer = VideoFrameAnalyzer(context, aiClassifier)
    val safeFileMover = SafeFileMover(context, timestampManager, storageScanner)
    val reportExporter = ReportExporter(context)
    val termuxExporter = TermuxOperationExporter(context)
    private val json = Json { encodeDefaults = true }

    private val _analysisProgress = MutableStateFlow(LiveAnalysisProgress())
    val analysisProgress: StateFlow<LiveAnalysisProgress> = _analysisProgress.asStateFlow()
    private val _transferProgress = MutableStateFlow(LiveTransferProgress())
    val transferProgress: StateFlow<LiveTransferProgress> = _transferProgress.asStateFlow()
    val categoriesFlow = db.categoryDao().observeAllCategories()
    val mediaItemsFlow: Flow<List<MediaItemEntity>> = settingsRepository.settingsFlow
        .map { it.sourceUri }.distinctUntilChanged().flatMapLatest { db.mediaItemDao().observeCurrentMediaItems(it) }
    val transferLogsFlow = db.transferLogDao().observeAllLogs()
    val sessionsFlow = db.analysisSessionDao().observeSessions()

    suspend fun ensureDefaultCategories() = db.seedDefaultCategories()
    suspend fun upsertCategory(category: CategoryEntity) {
        SafeRelativePath.segments(category.destinationSubfolder)
        db.categoryDao().upsertCategory(category)
    }
    suspend fun deleteCustomCategory(categoryId: String) = db.categoryDao().deleteCustomCategory(categoryId)
    suspend fun reassignMediaCategory(itemId: Long, newCategory: CategoryEntity) {
        val item = db.mediaItemDao().getMediaItemById(itemId) ?: return
        require(item.sourceRootUri == settingsRepository.settingsFlow.first().sourceUri) { "Item belongs to another source." }
        db.mediaItemDao().updateManualCategory(itemId, newCategory.id, newCategory.name,
            newCategory.destinationSubfolder + "/" + item.fileName)
    }
    suspend fun setExcludeFromTransfer(itemId: Long, excluded: Boolean) = db.mediaItemDao().updateExcludedState(itemId, excluded)
    suspend fun setAnalysisPaused(paused: Boolean) {
        settingsRepository.updateAnalysisPaused(paused)
        _analysisProgress.update { it.copy(isPaused = paused) }
    }
    fun markAnalysisQueued() { _analysisProgress.value = LiveAnalysisProgress(isRunning = true, isDiscovering = true) }
    fun markTransferQueued() { _transferProgress.value = LiveTransferProgress(isRunning = true) }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun signature(settings: AppSettings, categories: List<CategoryEntity>): String =
        digest("mlkit-image-labeling:17.0.9;diagnostic-onnx-v1;ort:1.22.0;" + settings.hardwareAcceleration + ";" + settings.aiEngineMode.name + ";" +
            settings.confidenceThreshold + ";" + json.encodeToString(categories.sortedBy { it.id }) + ";" + json.encodeToString(settings.cloudOptions) + ";" + CloudProtocol.VERSION + ";" +
            (if(settings.cloudOptions.mode == AnalysisMode.OFFLINE) 0 else cloud.vault.revision))

    private suspend fun checksum(uri: String, filePath: String? = null): String {
        val file = filePath?.let { File(it) } ?: storageScanner.resolveLocalFile(uri)
        val input = if (file != null) file.inputStream()
            else OriginalMediaAccess(context).input(Uri.parse(uri))
        return input.use {
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = it.read(buffer)
                if (size < 0) break
                hash.update(buffer, 0, size)
            }
            hash.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    suspend fun reconcileInterruptedTransfers() = withContext(Dispatchers.IO) {
        operationMutex.withLock { reconcileLocked() }
    }

    /** Recovery never deletes a source or resumes a pending deletion automatically. */
    private suspend fun reconcileLocked() {
        for (journal in db.transferJournalDao().unfinished()) {
            val item = db.mediaItemDao().getMediaItemById(journal.itemId)
            val verified = journal.destinationUri != null && journal.checksum != null &&
                runCatching { checksum(journal.destinationUri) == journal.checksum }.getOrDefault(false)
            val currentModifiedMs = journal.destinationUri?.let {
                timestampManager.resolveFilesystemLastModified(Uri.parse(it),
                    storageScanner.resolveLocalFile(it)?.path, 0)
            } ?: 0L
            val timestampNowVerified = verified && journal.timestampPreserved &&
                journal.originalModifiedMs > 0 && currentModifiedMs > 0 &&
                kotlin.math.abs(currentModifiedMs - journal.originalModifiedMs) <=
                    ExifAndTimestampManager.TIMESTAMP_TOLERANCE_MS
            val note = if (verified) "Interrupted operation: destination SHA-256 verified. Filesystem mtime currently verified: " +
                timestampNowVerified + ". Inspect source; no recovery deletion attempted."
                else "Interrupted operation requires inspection. Source deletion was not resumed."
            db.withTransaction {
                if (item != null) db.mediaItemDao().updateMediaItem(item.copy(
                    transferStatus = TransferItemStatus.RECOVERY_REQUIRED.name,
                    destinationUriString = journal.destinationUri,
                    sha256Checksum = journal.checksum ?: item.sha256Checksum,
                    needsManualReview = true, timestampVerified = timestampNowVerified,
                    timestampStatusNote = note
                ))
                db.transferJournalDao().upsert(journal.copy(phase = "RECOVERED", details = note,
                    destinationModifiedMs = currentModifiedMs, timestampPreserved = timestampNowVerified,
                    updatedAtMs = System.currentTimeMillis()))
            }
        }
    }

    suspend fun authorizeCloudBatch(settings: AppSettings): String = cloud.authorize(signature(settings, db.categoryDao().getAllCategories()), settings.cloudOptions)

    suspend fun executeMediaScanAndAnalysis(settings: AppSettings, cloudBatchId: String = "") = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            reconcileLocked()
            ensureDefaultCategories()
            val categories = db.categoryDao().getAllCategories()
            val enabled = categories.filter { it.isEnabled }
            require(enabled.isNotEmpty()) { "Enable at least one category." }
            val configuration = signature(settings, categories)
            var session = AnalysisSessionEntity(
                UUID.randomUUID().toString(), System.currentTimeMillis(), null,
                settings.sourceUri, settings.destinationUri, 0, 0, 0, 0, 0, "IN_PROGRESS", configuration
            )
            db.analysisSessionDao().upsertSession(session)
            _analysisProgress.value = LiveAnalysisProgress(isRunning = true, isDiscovering = true)
            var processed = 0
            var skipped = 0
            var uncertain = 0
            var failed = 0
            try {
                storageScanner.scanCandidates(settings.sourceUri, settings.destinationUri,
                    settings.includeSubfolders, settings.mediaTypeFilter, settings.enabledExtensions) {
                    count -> _analysisProgress.update { it.copy(totalFiles = count) }
                }.collect { candidate ->
                    currentCoroutineContext().ensureActive()
                    while (settingsRepository.settingsFlow.first().analysisPaused) {
                        _analysisProgress.update { it.copy(isPaused = true) }
                        currentCoroutineContext().ensureActive()
                        delay(250)
                    }
                    _analysisProgress.update { it.copy(isPaused = false, currentFileName = candidate.fileName,
                        currentPreviewUri = candidate.uri.toString()) }
                    val existing = db.mediaItemDao().getByUri(candidate.uri.toString())
                    var sourceChecksum: String? = null
                    try {
                        sourceChecksum = checksum(candidate.uri.toString(), candidate.filePath)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed++ }
                    val fingerprint = digest(candidate.uri.toString() + ";" + sourceChecksum + ";" + configuration)
                    if (sourceChecksum != null && existing != null &&
                        existing.sourceRootUri == settings.sourceUri && existing.quickFingerprint == fingerprint) {
                        val currentModified = timestampManager.resolveFilesystemLastModified(candidate.uri,
                            candidate.filePath, candidate.lastModifiedMs)
                        val timestampChanged = currentModified != existing.originalLastModifiedMs
                        db.mediaItemDao().updateMediaItem(existing.copy(scanSessionId = session.sessionId,
                            originalLastModifiedMs = currentModified,
                            transferStatus = if (timestampChanged && existing.transferStatus == TransferItemStatus.COMPLETED_VERIFIED.name)
                                TransferItemStatus.COMPLETED_TIMESTAMP_WARNING.name else existing.transferStatus,
                            timestampVerified = if (timestampChanged && existing.timestampVerified != null) false
                                else existing.timestampVerified,
                            timestampStatusNote = if (timestampChanged && existing.destinationUriString != null)
                                "Source filesystem mtime changed; the prior transfer timestamp result refers to the earlier snapshot."
                                else existing.timestampStatusNote))
                        if (existing.needsManualReview) uncertain++
                        skipped++
                    } else {
                        val metadata = timestampManager.inspectMediaMetadata(candidate.uri, candidate.filePath,
                            candidate.isVideo, candidate.lastModifiedMs)
                        var metrics = try {
                            suspend fun offline(): ClassificationScoreMetrics {
                            val engine = if (settings.cloudOptions.mode == AnalysisMode.HYBRID) AiEngineMode.HYBRID_ONNX_AND_MLKIT else settings.aiEngineMode
                            return if (candidate.isVideo) videoAnalyzer.analyzeVideo(candidate.uri, candidate.filePath,
                                metadata.durationMs, enabled, engine, "",
                                settings.hardwareAcceleration, settings.confidenceThreshold)
                            else aiClassifier.classifyImage(candidate.uri, candidate.filePath, metadata.orientationDegrees,
                                enabled, engine, "", settings.hardwareAcceleration,
                                settings.confidenceThreshold, metadata.isMirrored)
                            }
                            if (settings.cloudOptions.mode == AnalysisMode.OFFLINE) offline() else {
                                val local = if (settings.cloudOptions.mode == AnalysisMode.HYBRID) offline() else null
                                cloud.analyze(settings, cloudBatchId, configuration, sourceChecksum, enabled,
                                    candidate.uri, candidate.filePath, candidate.isVideo, metadata.orientationDegrees, metadata.isMirrored, local) {
                                    val live = settingsRepository.settingsFlow.first()
                                    if (live.sourceUri != settings.sourceUri || live.cloudOptions != settings.cloudOptions ||
                                        signature(live, db.categoryDao().getAllCategories()) != configuration)
                                        throw CloudFailure(CloudErrorKind.CONSENT)
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            failed++
                            ClassificationScoreMetrics("cat_other", "Other", 0f, 0f, 0f,
                                needsManualReview = true, primarySubjectNote = "Decoding/inference failed: " + error.message)
                        }
                        if (sourceChecksum == null) {
                            metrics = metrics.copy(needsManualReview = true,
                                primarySubjectNote = "Original-byte access unavailable; transfer is blocked. " + metrics.primarySubjectNote)
                        }
                        if (sourceChecksum != null) {
                            val unchanged = runCatching { checksum(candidate.uri.toString(), candidate.filePath) == sourceChecksum }
                                .getOrElse { error -> if (error is CancellationException) throw error else false }
                            if (!unchanged) {
                                sourceChecksum = null
                                metrics = metrics.copy(needsManualReview = true,
                                    primarySubjectNote = "Source changed during analysis; re-analyze before transfer.")
                            }
                        }
                        val preservedDecision = existing?.takeIf { it.sha256Checksum == sourceChecksum && sourceChecksum != null }
                        val manual = preservedDecision?.takeIf { it.isManuallyCorrected }?.let {
                            categories.firstOrNull { cat -> cat.id == it.effectiveCategoryId && cat.isEnabled }
                        }
                        val category = manual ?: enabled.firstOrNull { it.id == metrics.categoryId }
                        val needsReview = if (manual != null) false else metrics.needsManualReview
                        if (needsReview) uncertain++
                        val entity = MediaItemEntity(
                            id = existing?.id ?: 0, uriString = candidate.uri.toString(), filePath = candidate.filePath,
                            fileName = candidate.fileName, extension = candidate.extension, isVideo = candidate.isVideo,
                            mimeType = candidate.mimeType, fileSizeBytes = candidate.fileSizeBytes,
                            width = metadata.width, height = metadata.height, durationMs = metadata.durationMs,
                            originalLastModifiedMs = metadata.filesystemLastModifiedMs, exifCaptureDateMs = metadata.exifCaptureDateMs,
                            quickFingerprint = fingerprint, detectedCategoryId = metrics.categoryId, detectedCategoryName = metrics.categoryName,
                            effectiveCategoryId = category?.id ?: metrics.categoryId, effectiveCategoryName = category?.name ?: metrics.categoryName,
                            calibratedConfidence = metrics.calibratedConfidence, rawSimilarityScore = metrics.rawSimilarityScore,
                            topMargin = metrics.topMargin, secondaryCategoryName = metrics.secondaryCategoryName,
                            isAmbiguousMultiScene = metrics.isAmbiguousMultiScene, needsManualReview = needsReview,
                            isManuallyCorrected = manual != null, isExcludedFromTransfer = existing?.isExcludedFromTransfer ?: false,
                            primarySubjectNote = metrics.primarySubjectNote, frameBreakdownJson = json.encodeToString(metrics.frameSamples),
                            analyzedAtMs = System.currentTimeMillis(),
                            transferStatus = preservedDecision?.transferStatus ?: TransferItemStatus.PENDING.name,
                            proposedRelativePath = (category?.destinationSubfolder ?: "Other") + "/" + candidate.fileName,
                            destinationUriString = preservedDecision?.destinationUriString, sha256Checksum = sourceChecksum,
                            timestampVerified = preservedDecision?.timestampVerified, timestampStatusNote = preservedDecision?.timestampStatusNote,
                            sourceRootUri = settings.sourceUri, scanSessionId = session.sessionId, analysisSignature = configuration, inferenceEngine = metrics.inferenceEngine
                        )
                        db.mediaItemDao().upsertMediaItem(entity)
                    }
                    processed++
                    session = session.copy(totalScanned = _analysisProgress.value.totalFiles,
                        totalClassified = processed, uncertainCount = uncertain, failedCount = failed)
                    db.analysisSessionDao().upsertSession(session)
                    val currentCategory = db.mediaItemDao().getByUri(candidate.uri.toString())?.effectiveCategoryName.orEmpty()
                    _analysisProgress.update { it.copy(processedFiles = processed, skippedUnchangedCount = skipped,
                        currentDetectedCategory = currentCategory) }
                }
                session = session.copy(completedAtMs = System.currentTimeMillis(), totalScanned = processed, status = "COMPLETED")
                db.analysisSessionDao().upsertSession(session)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { db.analysisSessionDao().upsertSession(session.copy(status = "CANCELLED")) }
                _analysisProgress.update { it.copy(errorMessage = "CANCELLED") }
                throw cancelled
            } catch (error: Exception) {
                db.analysisSessionDao().upsertSession(session.copy(status = "FAILED"))
                _analysisProgress.update { it.copy(errorMessage = error.message) }
                throw error
            } finally {
                aiClassifier.close()
                _analysisProgress.update { it.copy(isRunning = false, isPaused = false, isDiscovering = false) }
            }
        }
    }

    suspend fun executeVerifiedTransfers(settings: AppSettings, includeReviewFlaggedItems: Boolean = false): List<SingleTransferOutcome> =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                reconcileLocked()
                val outcomes = mutableListOf<SingleTransferOutcome>()
                try {
                    require(settings.operationMode == OperationMode.MOVE_FILES &&
                        settingsRepository.settingsFlow.first().operationMode == OperationMode.MOVE_FILES) {
                        "Enable Organize Mode before confirming a transfer."
                    }
                    val session = db.analysisSessionDao().latestForSource(settings.sourceUri)
                        ?: error("Analyze this source first.")
                    require(session.status == "COMPLETED") { "Finish analysis of this source before transfer." }
                    val categories = db.categoryDao().getAllCategories()
                    require(session.analysisSignature == signature(settings, categories)) {
                        "Categories or AI settings changed; re-analyze."
                    }
                    val categoriesById = categories.associateBy { it.id }
                    val terminal = setOf(TransferItemStatus.COMPLETED_VERIFIED.name,
                        TransferItemStatus.COMPLETED_TIMESTAMP_WARNING.name, TransferItemStatus.COMPLETED_SOURCE_RETAINED.name,
                        TransferItemStatus.SKIPPED_COLLISION.name, TransferItemStatus.RECOVERY_REQUIRED.name)
                    val items = db.mediaItemDao().getCurrentMediaItems(settings.sourceUri).filter {
                        !it.isExcludedFromTransfer && it.transferStatus !in terminal && it.sha256Checksum != null &&
                        categoriesById[it.effectiveCategoryId]?.isEnabled == true &&
                        (it.inferenceEngine != "ONNX_DIAGNOSTIC" || it.isManuallyCorrected) &&
                        (!CloudApproval.requiresIndividualReview(it.inferenceEngine) || it.isManuallyCorrected) &&
                        (it.isManuallyCorrected || includeReviewFlaggedItems ||
                            !it.needsManualReview && it.calibratedConfidence >= settings.confidenceThreshold)
                    }
                    _transferProgress.value = LiveTransferProgress(isRunning = true, totalToTransfer = items.size)
                    var succeeded = 0
                    var failed = 0
                    var timestampVerified = 0
                    var collisions = 0
                    var retained = 0
                    var skipped = 0
                    var moved = 0
                    for (item in items) {
                        currentCoroutineContext().ensureActive()
                        val currentSettings = settingsRepository.settingsFlow.first()
                        require(currentSettings.operationMode == OperationMode.MOVE_FILES &&
                            currentSettings.sourceUri == settings.sourceUri &&
                            currentSettings.destinationUri == settings.destinationUri &&
                            currentSettings.transferMethod == settings.transferMethod &&
                            currentSettings.timestampPolicy == settings.timestampPolicy &&
                            signature(currentSettings, db.categoryDao().getAllCategories()) == session.analysisSignature) {
                            "Transfer settings changed; original preserved."
                        }
                        if (db.mediaItemDao().getMediaItemById(item.id)?.isExcludedFromTransfer == true) continue
                        val operationId = UUID.randomUUID().toString()
                        var journal = TransferJournalEntity(operationId, item.id, settings.sourceUri, item.uriString,
                            settings.transferMethod.name, "PREPARED", null, null, item.sha256Checksum,
                            item.originalLastModifiedMs, 0, false, System.currentTimeMillis(), "")
                        db.withTransaction {
                            db.transferJournalDao().upsert(journal)
                            db.mediaItemDao().updateMediaItem(item.copy(transferStatus = TransferItemStatus.IN_PROGRESS.name))
                        }
                        val category = checkNotNull(categoriesById[item.effectiveCategoryId])
                        _transferProgress.update { it.copy(currentFileName = item.fileName) }
                        val outcome = safeFileMover.transferMediaItem(session.sessionId, item, settings.sourceUri,
                            category.customDestinationUri?.takeIf { it.isNotBlank() } ?: settings.destinationUri,
                            category.destinationSubfolder, settings.operationMode, settings.transferMethod,
                            settings.collisionPolicy, settings.timestampPolicy) { checkpoint ->
                            if (checkpoint.phase == "SOURCE_DELETE_PENDING") {
                                val live = settingsRepository.settingsFlow.first()
                                require(live.operationMode == OperationMode.MOVE_FILES && live.sourceUri == settings.sourceUri &&
                                    live.destinationUri == settings.destinationUri &&
                                    live.transferMethod == settings.transferMethod &&
                                    live.timestampPolicy == settings.timestampPolicy &&
                                    signature(live, db.categoryDao().getAllCategories()) == session.analysisSignature &&
                                    db.mediaItemDao().getMediaItemById(item.id)?.let {
                                        !it.isExcludedFromTransfer && it.effectiveCategoryId == item.effectiveCategoryId &&
                                            it.transferStatus == TransferItemStatus.IN_PROGRESS.name
                                    } == true) {
                                    "Organize Mode revoked; original preserved."
                                }
                            }
                            journal = journal.copy(phase = checkpoint.phase, destinationUri = checkpoint.destinationId,
                                stagingUri = checkpoint.stagingId, checksum = checkpoint.checksum,
                                originalModifiedMs = checkpoint.originalModifiedMs,
                                destinationModifiedMs = checkpoint.destinationModifiedMs,
                                timestampPreserved = checkpoint.timestampPreserved, updatedAtMs = System.currentTimeMillis())
                            db.transferJournalDao().upsert(journal)
                        }
                        outcomes += outcome
                        db.withTransaction {
                            db.transferLogDao().insertLog(outcome.logEntry)
                            val currentItem = db.mediaItemDao().getMediaItemById(item.id) ?: item
                            db.mediaItemDao().updateMediaItem(currentItem.copy(transferStatus = outcome.status.name,
                                destinationUriString = outcome.destinationUriString, sha256Checksum = item.sha256Checksum,
                                timestampVerified = outcome.timestampPreserved, timestampStatusNote = outcome.detailsMessage,
                                needsManualReview = currentItem.needsManualReview || outcome.status == TransferItemStatus.RECOVERY_REQUIRED))
                            db.transferJournalDao().upsert(journal.copy(
                                phase = if (outcome.status == TransferItemStatus.FAILED || outcome.status == TransferItemStatus.RECOVERY_REQUIRED) "FAILED" else "FINALIZED",
                                details = outcome.detailsMessage))
                        }
                        when (outcome.status) {
                            TransferItemStatus.SKIPPED_COLLISION -> skipped++
                            TransferItemStatus.COMPLETED_VERIFIED, TransferItemStatus.COMPLETED_TIMESTAMP_WARNING,
                            TransferItemStatus.COMPLETED_SOURCE_RETAINED -> {
                                succeeded++
                                if (outcome.timestampPreserved) timestampVerified++
                                if (outcome.collisionResolutionNote != null) collisions++
                                if (outcome.logEntry.operationType == "MOVE") moved++ else retained++
                            }
                            else -> failed++
                        }
                        _transferProgress.value = LiveTransferProgress(true, items.size, outcomes.size, succeeded,
                            timestampVerified, collisions, failed, item.fileName, outcome.detailsMessage, retained, skipped)
                    }
                    db.analysisSessionDao().upsertSession(session.copy(movedCount = session.movedCount + moved,
                        failedCount = session.failedCount + failed))
                    outcomes
                } catch (cancelled: CancellationException) {
                    _transferProgress.update { it.copy(statusMessage = "Transfer cancelled; recovery never resumes source deletion.") }
                    throw cancelled
                } catch (error: Exception) {
                    _transferProgress.update { it.copy(statusMessage = error.message.orEmpty()) }
                    throw error
                } finally {
                    _transferProgress.update { it.copy(isRunning = false) }
                }
            }
        }

    private suspend fun exportSelection(ids: Set<Long>, destination: String, method: TransferMethod): List<FileOperationExportRecord> {
        val settings = settingsRepository.settingsFlow.first()
        val items = db.mediaItemDao().getCurrentMediaItems(settings.sourceUri).filter { it.id in ids && !it.isExcludedFromTransfer }
        val allCategories = db.categoryDao().getAllCategories()
        val categories = allCategories.associateBy { it.id }
        val session = db.analysisSessionDao().latestForSource(settings.sourceUri)
        val stale = session?.status != "COMPLETED" ||
            session.analysisSignature != signature(settings, allCategories)
        val checkedItems = items.map { it.copy(needsManualReview = it.needsManualReview || stale ||
            it.sha256Checksum == null || categories[it.effectiveCategoryId]?.isEnabled != true ||
            (it.inferenceEngine == "ONNX_DIAGNOSTIC" || CloudApproval.requiresIndividualReview(it.inferenceEngine)) && !it.isManuallyCorrected) }
        return termuxExporter.buildExportRecords(checkedItems, categories, destination, method, settings.sourceUri, settings.timestampPolicy)
    }
    suspend fun exportTermuxCsv(ids: Set<Long>, destination: String, method: TransferMethod): ExportBundleResult = withContext(Dispatchers.IO) {
        val records = exportSelection(ids, destination, method)
        val file = termuxExporter.exportClassificationCsv(records)
        ExportBundleResult(file.parent.orEmpty(), records.size, records.count { it.requiresSafAccessOnly }, listOf(file))
    }
    suspend fun exportTermuxJson(ids: Set<Long>, destination: String, method: TransferMethod): ExportBundleResult = withContext(Dispatchers.IO) {
        val records = exportSelection(ids, destination, method)
        val file = termuxExporter.exportFileOperationsJson(records)
        ExportBundleResult(file.parent.orEmpty(), records.size, records.count { it.requiresSafAccessOnly }, listOf(file))
    }
    suspend fun exportTermuxScript(ids: Set<Long>, destination: String, method: TransferMethod): ExportBundleResult = withContext(Dispatchers.IO) {
        val records = exportSelection(ids, destination, method)
        val file = termuxExporter.exportTermuxScript(records, method)
        ExportBundleResult(file.parent.orEmpty(), records.size, records.count { it.requiresSafAccessOnly }, listOf(file))
    }
    suspend fun exportTermuxAll(ids: Set<Long>, destination: String, method: TransferMethod): ExportBundleResult = withContext(Dispatchers.IO) {
        val records = exportSelection(ids, destination, method)
        val directory = termuxExporter.getExportDirectory()
        val files = listOf(termuxExporter.exportClassificationCsv(records, directory),
            termuxExporter.exportFileOperationsJson(records, directory),
            termuxExporter.exportTermuxScript(records, TransferMethod.MOVE, directory),
            termuxExporter.exportTermuxScript(records, TransferMethod.COPY, directory))
        ExportBundleResult(directory.absolutePath, records.size, records.count { it.requiresSafAccessOnly }, files)
    }
    suspend fun exportCsv(): File = withContext(Dispatchers.IO) {
        val items = db.mediaItemDao().getCurrentMediaItems(settingsRepository.settingsFlow.first().sourceUri)
        reportExporter.exportCsvReport(items, db.transferLogDao().getAllLogs().filter { log -> items.any { it.uriString == log.sourceUri } })
    }
    suspend fun exportJson(): File = withContext(Dispatchers.IO) {
        val items = db.mediaItemDao().getCurrentMediaItems(settingsRepository.settingsFlow.first().sourceUri)
        reportExporter.exportJsonDiagnostic(items, db.transferLogDao().getAllLogs().filter { log -> items.any { it.uriString == log.sourceUri } })
    }
}
