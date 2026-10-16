package com.aimediasorter.app.data.repository

import com.aimediasorter.app.data.diagnostics.*
import com.aimediasorter.app.domain.diagnostics.*
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

data class OperationPreview(val settings: AppSettings,val steps: List<PhysicalPlanEntity>,val names: Map<Long,String>)

data class LiveAnalysisProgress(
    val isRunning: Boolean = false, val isPaused: Boolean = false,
    val totalFiles: Int = 0, val processedFiles: Int = 0,
    val currentFileName: String = "", val currentDetectedCategory: String = "",
    val currentPreviewUri: String = "", val skippedUnchangedCount: Int = 0,
    val errorMessage: String? = null, val isDiscovering: Boolean = false,
    val successfulCount: Int = 0, val needsReviewCount: Int = 0, val failedCount: Int = 0,
    val pendingCount: Int = 0, val apiRequestsSent: Int = 0,
    val currentDiagnosticCode: String = "", val currentIsVideo: Boolean = false,
    val currentRequestsSent: Int = 0,val currentReservations: Int = 0,val currentHttpStatus: Int = 0
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
class MediaRepository(private val context: Context, database: AppDatabase?=null,
    settings: SettingsRepository?=null,cloudService: CloudAnalysisService?=null) {
    private val db = database ?: AppDatabase.getInstance(context)
    private val operationMutex = Mutex()
    val settingsRepository = settings ?: SettingsRepository(context)
    val timestampManager = ExifAndTimestampManager(context)
    val storageScanner = StorageScanner(context)
    val cloud = cloudService ?: CloudAnalysisService(context)
    val safeFileMover = SafeFileMover(context, timestampManager, storageScanner)
    val reportExporter = ReportExporter(context)
    val termuxExporter = TermuxOperationExporter(context)
    private val json = Json { encodeDefaults = true }

    private val _analysisProgress = MutableStateFlow(LiveAnalysisProgress())
    val analysisProgress: StateFlow<LiveAnalysisProgress> = _analysisProgress.asStateFlow()
    private val _transferProgress = MutableStateFlow(LiveTransferProgress())
    val transferProgress: StateFlow<LiveTransferProgress> = _transferProgress.asStateFlow()
    val galleryLinksFlow: Flow<List<MediaCategoryLink>> = settingsRepository.settingsFlow
        .map { it.sourceUri }.distinctUntilChanged().flatMapLatest { db.virtualGalleryDao().observeLinks(it) }
    val physicalPlansFlow = db.virtualGalleryDao().observePlans()
    val categoriesFlow = db.categoryDao().observeAllCategories()
    val mediaItemsFlow: Flow<List<MediaItemEntity>> = settingsRepository.settingsFlow
        .map { it.sourceUri }.distinctUntilChanged().flatMapLatest { db.mediaItemDao().observeCurrentMediaItems(it) }
    val transferLogsFlow = db.transferLogDao().observeAllLogs()
    val sessionsFlow = db.analysisSessionDao().observeSessions()

    suspend fun ensureDefaultCategories() = diagnosed(DiagnosticKind.DATABASE) { op -> op.measure(DiagnosticStage.DATABASE_WRITE) { db.seedDefaultCategories() } }
    suspend fun upsertCategory(category: CategoryEntity) = diagnosed(DiagnosticKind.SETTINGS) { op ->
        op.measure(DiagnosticStage.DATABASE_WRITE) {
            SafeRelativePath.segments(category.destinationSubfolder)
            db.categoryDao().upsertCategory(category)
        }
    }
    suspend fun deleteCustomCategory(categoryId: String) {
        operationMutex.withLock {
            db.withTransaction {
                if(db.categoryDao().getAllCategories().firstOrNull { it.id==categoryId }?.isDefault!=false) return@withTransaction
                for(id in db.virtualGalleryDao().affectedMedia(categoryId)) {
                    val item=db.mediaItemDao().getMediaItemById(id) ?: continue
                    db.mediaItemDao().updateMediaItem(item.copy(needsManualReview=true,isManuallyCorrected=false,
                        analysisStatus="NEEDS_REVIEW",effectiveCategoryId="__review__",effectiveCategoryName="Needs Review",
                        decisionVersion=item.decisionVersion+1))
                }
                db.categoryDao().deleteCustomCategory(categoryId)
            }
        }
    }
    suspend fun reassignMediaCategory(itemId: Long, newCategory: CategoryEntity) =
        updateVirtualCategories(setOf(itemId),setOf(newCategory.id),false)

    /** Stage B: transactionally changes database references only. */
    suspend fun updateVirtualCategories(ids: Set<Long>,categoryIds: Set<String>,defer: Boolean) =
        diagnosed(DiagnosticKind.CORRECTION) { op ->
            op.event(DiagnosticStage.CATEGORY_UPDATE,DiagnosticStatus.STARTED,metrics=mapOf(
                DiagnosticMetric.ITEM_COUNT to ids.size.toLong(),DiagnosticMetric.CATEGORY_COUNT to categoryIds.size.toLong()))
            updateVirtualCategoriesImpl(ids,categoryIds,defer)
            op.event(DiagnosticStage.DATABASE_WRITE,DiagnosticStatus.SUCCEEDED)
        }
    private suspend fun updateVirtualCategoriesImpl(ids: Set<Long>,categoryIds: Set<String>,defer: Boolean) {
        operationMutex.withLock {
            val settings=settingsRepository.settingsFlow.first()
            val cats=db.categoryDao().getAllCategories().associateBy { it.id }
            require(categoryIds.all { cats[it]?.isEnabled==true })
            require(defer || categoryIds.isNotEmpty()) { "Select a category or defer review." }
            db.withTransaction {
                for(id in ids) {
                    val item=db.mediaItemDao().getMediaItemById(id) ?: continue
                    require(item.sourceRootUri==settings.sourceUri)
                    require(item.transferStatus!=TransferItemStatus.IN_PROGRESS.name)
                    val one=categoryIds.singleOrNull()?.let { cats[it] }
                    db.virtualGalleryDao().clearLinks(id,"EFFECTIVE")
                    db.virtualGalleryDao().insertLinks(categoryIds.map { MediaCategoryLink(id,it,"EFFECTIVE") })
                    db.mediaItemDao().updateMediaItem(item.copy(
                        effectiveCategoryId=one?.id ?: "__review__",effectiveCategoryName=one?.name ?: "Multiple categories",
                        isManuallyCorrected=!defer,needsManualReview=defer || item.sha256Checksum==null,
                        analysisStatus=if(defer || item.sha256Checksum==null) "NEEDS_REVIEW" else "APPROVED",
                        decisionVersion=item.decisionVersion+1,
                        proposedRelativePath=one?.destinationSubfolder?.plus("/"+item.fileName).orEmpty()))
                }
            }
        }
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
        digest(CloudProtocol.VERSION + ";" + settings.sourceUri + ";" + settings.includeSubfolders + ";" +
            settings.mediaTypeFilter.name + ";" + settings.enabledExtensions.sorted().joinToString(",") + ";" +
            json.encodeToString(categories.sortedBy { it.id }) + ";" +
            json.encodeToString(settings.cloudOptions.copy(mode=AnalysisMode.GEMINI)) + ";" + cloud.vault.revision)


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

    suspend fun reconcileInterruptedTransfers() = diagnosed(DiagnosticKind.RECOVERY) { op ->
        reconcileInterruptedTransfersImpl()
        op.event(DiagnosticStage.PROCESS_RECOVERY,DiagnosticStatus.SUCCEEDED)
    }
    private suspend fun reconcileInterruptedTransfersImpl() = withContext(Dispatchers.IO) {
        operationMutex.withLock { reconcileLocked() }
    }

    /** Recovery never deletes a source or resumes a pending deletion automatically. */
    private suspend fun reconcileLocked() {
        db.virtualGalleryDao().recoverRunningPlans()
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

    private fun scope(ids: Set<Long>,force: Boolean)=digest(ids.sorted().joinToString(",")+";force="+force)
    suspend fun authorizeCloudBatch(settings: AppSettings,ids: Set<Long> = emptySet(),force: Boolean=false): String {
        require(ids.size<=100)
        return cloud.authorize(signature(settings,db.categoryDao().getAllCategories())+";scope="+scope(ids,force),settings.cloudOptions)
    }

    suspend fun executeMediaScanAndAnalysis(settings: AppSettings,cloudBatchId: String="",selectedIds: Set<Long> = emptySet(),forceReanalysis: Boolean=false) =
        diagnosed(DiagnosticKind.ANALYSIS_BATCH,batchId=cloudBatchId,model=settings.cloudOptions.model) { operation ->
            executeMediaScanAndAnalysisImpl(settings,cloudBatchId,selectedIds,forceReanalysis,operation)
        }
    private suspend fun executeMediaScanAndAnalysisImpl(settings: AppSettings,cloudBatchId: String,selectedIds: Set<Long>,forceReanalysis: Boolean,
        diagnostic: DiagnosticOperation) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            reconcileLocked()
            ensureDefaultCategories()
            val categories = db.categoryDao().getAllCategories()
            val enabled = categories.filter { it.isEnabled }
            require(enabled.isNotEmpty()) { "Enable at least one category." }
            val configuration = signature(settings, categories)
            var session = AnalysisSessionEntity(
                UUID.randomUUID().toString(), System.currentTimeMillis(), null,
                settings.sourceUri, settings.destinationUri, 0, 0, 0, 0, 0, "IN_PROGRESS", configuration,apiRequestsSent=0
            )
            db.analysisSessionDao().upsertSession(session)
            _analysisProgress.value = LiveAnalysisProgress(isRunning = true, isDiscovering = true)
            var classified = 0
            var processed = 0
            var skipped = 0
            var uncertain = 0
            var failed = 0
            var pending = 0
            diagnostic.event(DiagnosticStage.BATCH_SUMMARY,DiagnosticStatus.STARTED,metrics=summaryMetrics(session,selectedIds.size),sessionId=session.sessionId)
            try {
                storageScanner.scanCandidates(settings.sourceUri, "",
                    settings.includeSubfolders, settings.mediaTypeFilter, settings.enabledExtensions) {
                    count -> _analysisProgress.update { it.copy(totalFiles = count) }
                }.collect { candidate ->
                    currentCoroutineContext().ensureActive()
                    while (settingsRepository.settingsFlow.first().analysisPaused) {
                        _analysisProgress.update { it.copy(isPaused = true) }
                        currentCoroutineContext().ensureActive()
                        delay(250)
                    }
                    _analysisProgress.update { it.copy(isPaused = false, isDiscovering = false, currentFileName = candidate.fileName,
                        currentPreviewUri = candidate.uri.toString(),currentDiagnosticCode="",currentIsVideo=candidate.isVideo) }
                    val existing = db.mediaItemDao().getByUri(candidate.uri.toString())
                    if(selectedIds.isNotEmpty() && existing?.id !in selectedIds) {
                        if(existing!=null) db.mediaItemDao().updateMediaItem(existing.copy(scanSessionId=session.sessionId))
                        return@collect
                    }
                    val fileDiagnostic=Diagnostics.operation(DiagnosticKind.MEDIA_ANALYSIS,batchId=diagnostic.batchId,
                        parentId=diagnostic.id,model=settings.cloudOptions.model)
                    fileDiagnostic.event(DiagnosticStage.SOURCE_READ,DiagnosticStatus.STARTED,metrics=mapOf(
                        DiagnosticMetric.ITEM_INDEX to (processed+1).toLong(),DiagnosticMetric.VIDEO to if(candidate.isVideo) 1L else 0L))
                    var sourceChecksum: String? = null
                    try {
                        sourceChecksum = checksum(candidate.uri.toString(), candidate.filePath)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Transfer remains blocked; analysis may read provider-redacted selected pixels. */ }
                    fileDiagnostic.event(DiagnosticStage.ORIGINAL_ACCESS,if(sourceChecksum!=null) DiagnosticStatus.SUCCEEDED else DiagnosticStatus.NEEDS_REVIEW,
                        code=if(sourceChecksum==null) AnalysisCode.LOCAL_SOURCE_ACCESS else null)
                    // Analysis identity may use provider-redacted selected bytes. It is never stored
                    // as the original SHA-256 or accepted by the transfer planner.
                    val analysisIdentity = sourceChecksum ?: try {
                        SelectedMediaAccess(context).fingerprint(candidate.uri)
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) { null }
                    val fingerprint = digest(candidate.uri.toString() + ";" + sourceChecksum + ";" + configuration)
                    if (!forceReanalysis && sourceChecksum != null && existing != null &&
                        existing.sourceRootUri == settings.sourceUri && existing.quickFingerprint == fingerprint &&
                        existing.analysisStatus !in setOf("FAILED","PENDING") &&
                        CloudApproval.shouldReuse(settings.cloudOptions.mode,existing.primarySubjectNote,
                            existing.analyzedAtMs,existing.isManuallyCorrected)) {
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
                                else existing.timestampStatusNote,
                            diagnosticCode=AnalysisCode.CACHE_HIT.name,apiRequestsSent=0,requestReservations=0,responseHttpStatus=0))
                        skipped++
                        fileDiagnostic.event(DiagnosticStage.CACHE_HIT,DiagnosticStatus.SUCCEEDED,code=AnalysisCode.CACHE_HIT)
                        fileDiagnostic.event(DiagnosticStage.REQUEST_NOT_SENT,DiagnosticStatus.INFO,code=AnalysisCode.CACHE_HIT)
                    } else {
                        val metadata = timestampManager.inspectMediaMetadata(candidate.uri, candidate.filePath,
                            candidate.isVideo, candidate.lastModifiedMs)
                        var metrics = try {
                            cloud.analyze(settings, cloudBatchId, configuration+";scope="+scope(selectedIds,forceReanalysis), analysisIdentity, enabled,
                                candidate.uri, candidate.filePath, candidate.isVideo, metadata.orientationDegrees, metadata.isMirrored,
                                force=forceReanalysis,diagnostic=fileDiagnostic) {
                                val live = settingsRepository.settingsFlow.first()
                                if (live.sourceUri != settings.sourceUri || live.cloudOptions != settings.cloudOptions ||
                                    signature(live, db.categoryDao().getAllCategories()) != configuration)
                                    throw CloudFailure(CloudErrorKind.CONSENT)
                                val unchanged=if(sourceChecksum!=null)
                                    checksum(candidate.uri.toString(),candidate.filePath)==sourceChecksum
                                    else analysisIdentity!=null && SelectedMediaAccess(context).fingerprint(candidate.uri)==analysisIdentity
                                if(!unchanged) throw CloudFailure(CloudErrorKind.CONSENT,AnalysisCode.LOCAL_SOURCE_CHANGED)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            ClassificationScoreMetrics("__failed__", "Analysis Failed", 0f, 0f, 0f,
                                needsManualReview = true, primarySubjectNote = AnalysisCode.INTERNAL.message,
                                inferenceEngine="GEMINI",analysisStatus="FAILED",analysisModel=settings.cloudOptions.model,
                                diagnosticCode=AnalysisCode.INTERNAL.name,apiRequestsSent=0,requestReservations=0)
                        }
                        if (sourceChecksum == null) {
                            metrics = metrics.copy(needsManualReview = true,
                                analysisStatus=if(metrics.analysisStatus in setOf("PROPOSED","NEEDS_REVIEW")) "NEEDS_REVIEW" else metrics.analysisStatus,
                                primarySubjectNote = "Original-byte access unavailable; transfer is blocked. " + metrics.primarySubjectNote)
                        }
                        if (sourceChecksum != null) {
                            val unchanged = runCatching { checksum(candidate.uri.toString(), candidate.filePath) == sourceChecksum }
                                .getOrElse { error -> if (error is CancellationException) throw error else false }
                            if (!unchanged) {
                                sourceChecksum = null
                                metrics = metrics.copy(needsManualReview = true,
                                    primarySubjectNote = "Source changed during analysis; re-analyze before transfer.",
                                    proposedCategoryIds=emptyList(),analysisStatus="FAILED",analysisJson="{}",diagnosticCode=AnalysisCode.LOCAL_SOURCE_CHANGED.name)
                            }
                        }
                        val preservedLinks = existing?.let { db.virtualGalleryDao().links(it.id).filter { link -> link.origin=="EFFECTIVE" }.map { link -> link.categoryId } }.orEmpty()
                        val preservedDecision = existing?.takeIf { it.sha256Checksum == sourceChecksum && sourceChecksum != null }
                        val manual = preservedDecision?.takeIf { it.isManuallyCorrected }?.let {
                            categories.firstOrNull { cat -> cat.id == it.effectiveCategoryId && cat.isEnabled }
                        }
                        val category = manual ?: enabled.firstOrNull { it.id == metrics.categoryId }
                        val preserveManual = preservedDecision?.isManuallyCorrected==true && preservedLinks.isNotEmpty() && preservedLinks.all { id -> enabled.any { it.id==id } }
                        val validAnalysis=metrics.analysisStatus in setOf("PROPOSED","NEEDS_REVIEW")
                        val needsReview = if (preserveManual && validAnalysis) sourceChecksum==null else metrics.needsManualReview
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
                            isManuallyCorrected = preserveManual, isExcludedFromTransfer = existing?.isExcludedFromTransfer ?: false,
                            primarySubjectNote = metrics.primarySubjectNote, frameBreakdownJson = json.encodeToString(metrics.frameSamples),
                            analyzedAtMs = System.currentTimeMillis(),
                            transferStatus = preservedDecision?.transferStatus ?: TransferItemStatus.PENDING.name,
                            proposedRelativePath = (category?.destinationSubfolder ?: "Other") + "/" + candidate.fileName,
                            destinationUriString = preservedDecision?.destinationUriString, sha256Checksum = sourceChecksum,
                            timestampVerified = preservedDecision?.timestampVerified, timestampStatusNote = preservedDecision?.timestampStatusNote,
                            sourceRootUri = settings.sourceUri, scanSessionId = session.sessionId, analysisSignature = configuration, inferenceEngine = metrics.inferenceEngine,
                            analysisStatus=if(preserveManual && validAnalysis) "APPROVED" else metrics.analysisStatus,
                            analysisModel=metrics.analysisModel,analysisJson=metrics.analysisJson,
                            decisionVersion=(existing?.decisionVersion ?: 0)+1,
                            diagnosticCode=metrics.diagnosticCode,apiRequestsSent=metrics.apiRequestsSent,
                            requestReservations=metrics.requestReservations,responseHttpStatus=metrics.responseHttpStatus
                        )
                        fileDiagnostic.measure(DiagnosticStage.DATABASE_WRITE) { db.withTransaction {
                            val id=db.mediaItemDao().upsertMediaItem(entity)
                            db.virtualGalleryDao().clearLinks(id,"PROPOSED")
                            db.virtualGalleryDao().clearLinks(id,"EFFECTIVE")
                            db.virtualGalleryDao().insertLinks(metrics.proposedCategoryIds.map { MediaCategoryLink(id,it,"PROPOSED") })
                            val effective=if(preserveManual) preservedLinks else metrics.proposedCategoryIds
                            db.virtualGalleryDao().insertLinks(effective.map { MediaCategoryLink(id,it,"EFFECTIVE") })
                        } }
                    }
                    fileDiagnostic.event(DiagnosticStage.DATABASE_WRITE,DiagnosticStatus.SUCCEEDED)
                    processed++
                    val currentRow=db.mediaItemDao().getByUri(candidate.uri.toString())
                    when {
                        currentRow?.analysisStatus=="FAILED"->failed++
                        currentRow?.analysisStatus=="PENDING"->pending++
                        currentRow!=null->{classified++;if(currentRow.needsManualReview) uncertain++}
                    }
                    session = session.copy(totalScanned = _analysisProgress.value.totalFiles,
                        totalClassified = classified, uncertainCount = uncertain, failedCount = failed,
                        successfulCount=classified-uncertain,pendingCount=pending,apiRequestsSent=cloud.state.sentInBatch(cloudBatchId))
                    db.analysisSessionDao().upsertSession(session)
                    fileDiagnostic.event(DiagnosticStage.COMPLETE,when(currentRow?.analysisStatus) {
                        "FAILED"->DiagnosticStatus.FAILED;"PENDING"->DiagnosticStatus.PENDING
                        else->if(currentRow?.needsManualReview==true) DiagnosticStatus.NEEDS_REVIEW else DiagnosticStatus.SUCCEEDED
                    },code=AnalysisCode.safely(currentRow?.diagnosticCode.orEmpty()),http=currentRow?.responseHttpStatus?.takeIf { it>0 },
                        metrics=mapOf(DiagnosticMetric.REQUESTS_SENT to (currentRow?.apiRequestsSent ?: 0).toLong(),
                            DiagnosticMetric.RESERVATIONS to (currentRow?.requestReservations ?: 0).toLong()))
                    diagnostic.event(DiagnosticStage.BATCH_SUMMARY,DiagnosticStatus.STARTED,metrics=summaryMetrics(session,if(selectedIds.isNotEmpty()) selectedIds.size else session.totalScanned),sessionId=session.sessionId)
                    val currentCategory = db.mediaItemDao().getByUri(candidate.uri.toString())?.effectiveCategoryName.orEmpty()
                    _analysisProgress.update { it.copy(processedFiles = processed, skippedUnchangedCount = skipped,
                        currentDetectedCategory = currentCategory,currentDiagnosticCode=currentRow?.diagnosticCode.orEmpty(),
                        currentRequestsSent=currentRow?.apiRequestsSent ?: 0,currentReservations=currentRow?.requestReservations ?: 0,
                        currentHttpStatus=currentRow?.responseHttpStatus ?: 0,
                        successfulCount=session.successfulCount,needsReviewCount=uncertain,failedCount=failed,
                        pendingCount=pending,apiRequestsSent=session.apiRequestsSent) }
                }
                _analysisProgress.update { it.copy(totalFiles=processed) }
                session = session.copy(completedAtMs = System.currentTimeMillis(), totalScanned = processed, status = "COMPLETED")
                db.analysisSessionDao().upsertSession(session)
                diagnostic.event(DiagnosticStage.BATCH_SUMMARY,DiagnosticStatus.SUCCEEDED,metrics=summaryMetrics(session,if(selectedIds.isNotEmpty()) selectedIds.size else session.totalScanned),sessionId=session.sessionId)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { db.analysisSessionDao().upsertSession(session.copy(status = "CANCELLED")) }
                diagnostic.event(DiagnosticStage.BATCH_SUMMARY,DiagnosticStatus.CANCELLED,metrics=summaryMetrics(session,if(selectedIds.isNotEmpty()) selectedIds.size else session.totalScanned),sessionId=session.sessionId)
                _analysisProgress.update { it.copy(errorMessage = "CANCELLED") }
                throw cancelled
            } catch (error: Exception) {
                db.analysisSessionDao().upsertSession(session.copy(status = "FAILED"))
                diagnostic.event(DiagnosticStage.BATCH_SUMMARY,DiagnosticStatus.FAILED,metrics=summaryMetrics(session,if(selectedIds.isNotEmpty()) selectedIds.size else session.totalScanned),sessionId=session.sessionId)
                _analysisProgress.update { it.copy(errorMessage = when(error) {
                    is CloudFailure->error.diagnostic.message
                    is SecurityException->AnalysisCode.LOCAL_SOURCE_ACCESS.message
                    else->AnalysisCode.INTERNAL.message
                }) }
                throw error
            } finally {
                _analysisProgress.update { it.copy(isRunning = false, isPaused = false, isDiscovering = false) }
            }
        }
    }

    /** Export-only fallback for pre-logger installations. Reads saved results, never original media. */
    suspend fun storedAnalysisDiagnostics(afterClearMs: Long=0L,recordedSessionIds: Set<String> = emptySet()): Pair<String,DiagnosticSnapshot>? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val session=db.analysisSessionDao().latest() ?: return@withTransaction null
            if(session.startedAtMs<=afterClearMs || session.sessionId in recordedSessionIds) return@withTransaction null
            val batch=DiagnosticPrivacy.id(session.sessionId) ?: UUID.nameUUIDFromBytes(session.sessionId.toByteArray(Charsets.UTF_8)).toString()
            // Unselected/cached rows may share a scan ID without a fresh analysis. Their old
            // execution cannot be reconstructed, so do not pretend they were analyzed in this batch.
            val items=db.mediaItemDao().getCurrentMediaItems(session.sourceUri)
                .filter { it.scanSessionId==session.sessionId && it.analyzedAtMs>=session.startedAtMs }
            val files=items.map { item ->
                val status=when(item.analysisStatus) {
                    "FAILED"->DiagnosticStatus.FAILED;"PENDING"->DiagnosticStatus.PENDING
                    else->if(item.needsManualReview) DiagnosticStatus.NEEDS_REVIEW else DiagnosticStatus.SUCCEEDED
                }
                DiagnosticEvent(item.analyzedAtMs,UUID.nameUUIDFromBytes((batch+":"+item.id).toByteArray(Charsets.UTF_8)).toString(),
                    parentId=batch,batchId=batch,kind=DiagnosticKind.MEDIA_ANALYSIS,stage=DiagnosticStage.LEGACY_SUMMARY,status=status,
                    error=DiagnosticError.HISTORICAL_DETAILS_UNAVAILABLE,analysisCode=AnalysisCode.safely(item.diagnosticCode).name,
                    httpStatus=item.responseHttpStatus.takeIf { it in 100..599 },model=DiagnosticPrivacy.model(item.analysisModel),
                    metrics=buildMap {
                        if(item.apiRequestsSent>=0) put(DiagnosticMetric.REQUESTS_SENT,item.apiRequestsSent.toLong())
                        if(item.requestReservations>=0) put(DiagnosticMetric.RESERVATIONS,item.requestReservations.toLong())
                    },sessionId=batch,durationKnown=false)
            }
            val summary=DiagnosticEvent(session.completedAtMs ?: session.startedAtMs,batch,batchId=batch,
                kind=DiagnosticKind.ANALYSIS_BATCH,stage=DiagnosticStage.BATCH_SUMMARY,
                status=when(session.status) { "COMPLETED"->DiagnosticStatus.SUCCEEDED;"FAILED"->DiagnosticStatus.FAILED;"CANCELLED"->DiagnosticStatus.CANCELLED;else->DiagnosticStatus.PENDING },
                model=files.mapNotNull { it.model }.distinct().singleOrNull(),error=DiagnosticError.HISTORICAL_DETAILS_UNAVAILABLE,
                metrics=buildMap {
                    if(session.totalScanned>=0) put(DiagnosticMetric.TOTAL_FILES,session.totalScanned.toLong())
                    if(session.successfulCount>=0) put(DiagnosticMetric.SUCCESSFUL,session.successfulCount.toLong())
                    if(session.uncertainCount>=0) put(DiagnosticMetric.NEEDS_REVIEW,session.uncertainCount.toLong())
                    if(session.failedCount>=0) put(DiagnosticMetric.FAILED,session.failedCount.toLong())
                    if(session.pendingCount>=0) put(DiagnosticMetric.PENDING,session.pendingCount.toLong())
                    if(session.apiRequestsSent>=0) put(DiagnosticMetric.REQUESTS_SENT,session.apiRequestsSent.toLong())
                },sessionId=batch,durationKnown=false)
            session.sessionId to DiagnosticSnapshot((files+summary).sortedByDescending { it.timestampMs },listOf(summary))
        }
    }

    private fun summaryMetrics(session: AnalysisSessionEntity,total: Int)=mapOf(
        DiagnosticMetric.TOTAL_FILES to total.toLong(),
        DiagnosticMetric.SUCCESSFUL to session.successfulCount.toLong(),DiagnosticMetric.NEEDS_REVIEW to session.uncertainCount.toLong(),
        DiagnosticMetric.FAILED to session.failedCount.toLong(),DiagnosticMetric.PENDING to session.pendingCount.toLong(),
        DiagnosticMetric.REQUESTS_SENT to session.apiRequestsSent.toLong(),
        DiagnosticMetric.PROCESSED to (session.totalClassified+session.failedCount+session.pendingCount).toLong())

    /** Legacy entry point cannot turn classification approval into an implicit operation plan. */
    suspend fun executeVerifiedTransfers(settings: AppSettings,includeReviewFlaggedItems: Boolean=false): List<SingleTransferOutcome> =
        error("Use a selected-media physical operation preview and explicitly approve that plan.")

    suspend fun previewOperations(ids: Set<Long>,method: TransferMethod,moveTargets: Map<Long,String>): OperationPreview =
        diagnosed(DiagnosticKind.OPERATION_PLAN) { op ->
            op.event(DiagnosticStage.PREVIEW,DiagnosticStatus.STARTED,metrics=mapOf(DiagnosticMetric.ITEM_COUNT to ids.size.toLong()))
            previewOperationsImpl(ids,method,moveTargets)
        }
    private suspend fun previewOperationsImpl(ids: Set<Long>,method: TransferMethod,moveTargets: Map<Long,String>): OperationPreview =
        operationMutex.withLock {
            require(ids.size in 1..100) { "Select 1 to 100 media items per physical plan." }
            val settings=settingsRepository.settingsFlow.first()
            require(settings.destinationUri.isNotBlank()) { "Select a destination folder first." }
            val categories=db.categoryDao().getAllCategories().associateBy { it.id }
            val group=UUID.randomUUID().toString()
            val names=mutableMapOf<Long,String>()
            val steps=buildList {
                for(id in ids.sorted()) {
                    val item=db.mediaItemDao().getMediaItemById(id) ?: error("Media is unavailable.")
                    require(item.sourceRootUri==settings.sourceUri && !item.isExcludedFromTransfer)
                    require(!item.needsManualReview && item.analysisStatus in setOf("PROPOSED","APPROVED")) {
                        "Resolve review or analysis failure before physical organization."
                    }
                    require(item.sha256Checksum!=null && checksum(item.uriString,item.filePath)==item.sha256Checksum) {
                        "Original bytes changed or are unavailable; reanalyze first."
                    }
                    names[id]=item.fileName
                    val links=db.virtualGalleryDao().links(id).filter { it.origin=="EFFECTIVE" }.map { it.categoryId }.toSet()
                    for(step in OperationPlanner.build(id,links,method,moveTargets[id] ?: links.singleOrNull())) {
                        val cat=categories[step.categoryId] ?: error("Category was deleted.")
                        require(cat.isEnabled)
                        val destination=cat.customDestinationUri?.takeIf { it.isNotBlank() } ?: settings.destinationUri
                        require(!storageScanner.isDestinationInsideSource(settings.sourceUri,destination))
                        SafeRelativePath.segments(cat.destinationSubfolder)
                        add(PhysicalPlanEntity(UUID.randomUUID().toString(),id,cat.id,step.method.name,
                            item.sha256Checksum,item.decisionVersion,destination,cat.destinationSubfolder,group,size))
                    }
                }
            }
            require(steps.map { Triple(it.mediaId,it.destinationRoot,it.destinationFolder) }.distinct().size==steps.size) {
                "Two categories use the same physical destination. Choose distinct destinations before copying."
            }
            require(steps.size<=100) { "A physical batch supports at most 100 destination operations. Reduce selection." }
            OperationPreview(settings,steps,names)
        }

    /** Persist only after explicit UI confirmation; preview and virtual corrections never reach storage writes. */
    suspend fun approveOperationPreview(preview: OperationPreview): List<String> = diagnosed(DiagnosticKind.OPERATION_PLAN) { op ->
        val result=approveOperationPreviewImpl(preview)
        op.event(DiagnosticStage.APPROVAL,DiagnosticStatus.SUCCEEDED,metrics=mapOf(DiagnosticMetric.ITEM_COUNT to result.size.toLong()))
        result
    }
    private suspend fun approveOperationPreviewImpl(preview: OperationPreview): List<String> = operationMutex.withLock {
        val current=settingsRepository.settingsFlow.first()
        require(current.sourceUri==preview.settings.sourceUri && current.destinationUri==preview.settings.destinationUri &&
            current.timestampPolicy==preview.settings.timestampPolicy && current.collisionPolicy==preview.settings.collisionPolicy &&
            current.operationMode==OperationMode.MOVE_FILES) { "Operation settings changed. Preview again." }
        require(preview.steps.isNotEmpty())
        db.withTransaction {
            for(step in preview.steps) {
                val item=requireNotNull(db.mediaItemDao().getMediaItemById(step.mediaId))
                require(item.decisionVersion==step.decisionVersion && item.sha256Checksum==step.sourceHash &&
                    !item.needsManualReview && !item.isExcludedFromTransfer && item.sourceRootUri==current.sourceUri)
                require(db.virtualGalleryDao().links(item.id).any { it.origin=="EFFECTIVE" && it.categoryId==step.categoryId })
                val prior=db.virtualGalleryDao().plansForMedia(item.id)
                require(prior.none { it.sourceHash==step.sourceHash && it.destinationRoot==step.destinationRoot &&
                    it.destinationFolder==step.destinationFolder && it.status !in setOf("CANCELLED","FAILED") }) {
                    "A matching operation already exists. Inspect its destination before creating another copy."
                }
            }
            db.virtualGalleryDao().insertPlans(preview.steps)
        }
        preview.steps.map { it.id }
    }

    suspend fun cancelPhysicalPlans() {
        db.withTransaction {
            db.virtualGalleryDao().cancelUnstartedPlans()
            db.virtualGalleryDao().requestCancelRunningPlans()
        }
    }

    suspend fun executePhysicalPlans(settings: AppSettings,ids: List<String>) =
        diagnosed(DiagnosticKind.OPERATION_PLAN) { operation -> executePhysicalPlansImpl(settings,ids,operation) }
    private suspend fun executePhysicalPlansImpl(settings: AppSettings,ids: List<String>,diagnostic: DiagnosticOperation) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            reconcileLocked()
            require(ids.size in 1..100 && ids.distinct().size==ids.size)
            val steps=db.virtualGalleryDao().plans(ids)
            require(steps.size==ids.size && steps.all { it.status=="APPROVED" }) { "Plan already executed or requires recovery review." }
            _transferProgress.value=LiveTransferProgress(isRunning=true,totalToTransfer=steps.size)
            val successful=mutableListOf<PhysicalPlanEntity>()
            var done=0;var failed=0;var retained=0;var timestampCount=0
            try {
                for(step in steps) {
                    val transferDiagnostic=Diagnostics.operation(if(step.method=="MOVE") DiagnosticKind.MOVE else DiagnosticKind.COPY,
                        batchId=step.groupId,parentId=diagnostic.id)
                    try {
                    transferDiagnostic.event(DiagnosticStage.PREFLIGHT,DiagnosticStatus.STARTED)
                    currentCoroutineContext().ensureActive()
                    val live=settingsRepository.settingsFlow.first()
                    require(live.operationMode==OperationMode.MOVE_FILES && live.sourceUri==settings.sourceUri &&
                        live.destinationUri==settings.destinationUri && live.timestampPolicy==settings.timestampPolicy &&
                        live.collisionPolicy==settings.collisionPolicy) { "Operation permission or settings changed." }
                    val item=requireNotNull(db.mediaItemDao().getMediaItemById(step.mediaId))
                    val category=db.categoryDao().getAllCategories().firstOrNull { it.id==step.categoryId } ?: error("Category removed.")
                    require(category.isEnabled && category.destinationSubfolder==step.destinationFolder &&
                        (category.customDestinationUri?.takeIf { it.isNotBlank() } ?: live.destinationUri)==step.destinationRoot)
                    require(item.decisionVersion==step.decisionVersion && item.sha256Checksum==step.sourceHash &&
                        !item.needsManualReview && !item.isExcludedFromTransfer && item.sourceRootUri==live.sourceUri)
                    require(checksum(item.uriString,item.filePath)==step.sourceHash) { "Original changed; operation blocked." }
                    if(step.method=="MOVE") {
                        val required=steps.filter { it.mediaId==step.mediaId && it.sequence<step.sequence }
                        require(required.all { previous -> successful.any { it.id==previous.id } }) { "A preceding copy did not complete." }
                        for(copy in successful.filter { it.mediaId==step.mediaId }) {
                            require(copy.destinationUri!=null && checksum(copy.destinationUri)==step.sourceHash) {
                                "An additional destination no longer verifies. Original retained."
                            }
                        }
                    }
                    transferDiagnostic.event(DiagnosticStage.PREFLIGHT,DiagnosticStatus.SUCCEEDED)
                    val journalId=step.id
                    var journal=TransferJournalEntity(journalId,item.id,live.sourceUri,item.uriString,step.method,
                        "PREPARED",null,null,step.sourceHash,item.originalLastModifiedMs,0,false,System.currentTimeMillis(),"")
                    db.withTransaction {
                        require(db.virtualGalleryDao().plans(listOf(step.id)).singleOrNull()?.status=="APPROVED") { "Plan cancelled before transfer." }
                        val currentItem=requireNotNull(db.mediaItemDao().getMediaItemById(item.id))
                        require(currentItem.decisionVersion==step.decisionVersion && !currentItem.isExcludedFromTransfer)
                        db.virtualGalleryDao().updatePlan(step.copy(status="RUNNING"))
                        db.transferJournalDao().upsert(journal)
                        db.mediaItemDao().updateMediaItem(currentItem.copy(transferStatus=TransferItemStatus.IN_PROGRESS.name))
                    }
                    _transferProgress.update { it.copy(currentFileName=item.fileName) }
                    val outcome=safeFileMover.transferMediaItem(step.groupId,item.copy(effectiveCategoryId=category.id,
                        effectiveCategoryName=category.name),live.sourceUri,step.destinationRoot,step.destinationFolder,
                        OperationMode.MOVE_FILES,TransferMethod.valueOf(step.method),live.collisionPolicy,live.timestampPolicy) { checkpoint ->
                        currentCoroutineContext().ensureActive()
                        transferDiagnostic.event(if(checkpoint.phase=="SOURCE_DELETE_PENDING") DiagnosticStage.SOURCE_DELETE_PENDING else DiagnosticStage.TRANSFER_CHECKPOINT,
                            DiagnosticStatus.INFO,metrics=mapOf(DiagnosticMetric.TIMESTAMP_PRESERVED to if(checkpoint.timestampPreserved) 1L else 0L),
                            transferPhase=DiagnosticTransferPhase.entries.firstOrNull { it.name==checkpoint.phase } ?: DiagnosticTransferPhase.OTHER)
                        if(checkpoint.phase=="SOURCE_DELETE_PENDING") {
                            val now=settingsRepository.settingsFlow.first()
                            val row=db.mediaItemDao().getMediaItemById(item.id)
                            require(db.virtualGalleryDao().plans(listOf(step.id)).singleOrNull()?.status=="RUNNING") { "Transfer approval revoked; source retained." }
                            require(now.operationMode==OperationMode.MOVE_FILES && now.sourceUri==live.sourceUri &&
                                now.destinationUri==live.destinationUri && now.timestampPolicy==live.timestampPolicy &&
                                row!=null && row.decisionVersion==step.decisionVersion && !row.isExcludedFromTransfer)
                            val currentCat=db.categoryDao().getAllCategories().firstOrNull { it.id==step.categoryId }
                            require(currentCat?.isEnabled==true && currentCat.destinationSubfolder==step.destinationFolder &&
                                (currentCat.customDestinationUri?.takeIf { it.isNotBlank() } ?: now.destinationUri)==step.destinationRoot)
                            for(copy in successful.filter { it.mediaId==step.mediaId })
                                require(copy.destinationUri!=null && checksum(copy.destinationUri)==step.sourceHash)
                        }
                        journal=journal.copy(phase=checkpoint.phase,destinationUri=checkpoint.destinationId,
                            stagingUri=checkpoint.stagingId,checksum=checkpoint.checksum,
                            originalModifiedMs=checkpoint.originalModifiedMs,destinationModifiedMs=checkpoint.destinationModifiedMs,
                            timestampPreserved=checkpoint.timestampPreserved,updatedAtMs=System.currentTimeMillis())
                        db.transferJournalDao().upsert(journal)
                    }
                    val verified=outcome.status in setOf(TransferItemStatus.COMPLETED_VERIFIED,
                        TransferItemStatus.COMPLETED_TIMESTAMP_WARNING,TransferItemStatus.COMPLETED_SOURCE_RETAINED) &&
                        outcome.sha256Checksum==step.sourceHash && outcome.destinationUriString!=null
                    transferDiagnostic.event(DiagnosticStage.INTEGRITY_VERIFIED,if(verified) DiagnosticStatus.SUCCEEDED else DiagnosticStatus.FAILED,
                        error=if(verified) DiagnosticError.NONE else DiagnosticError.TRANSFER_NOT_VERIFIED,
                        metrics=mapOf(DiagnosticMetric.VERIFIED to if(verified) 1L else 0L))
                    transferDiagnostic.event(DiagnosticStage.TIMESTAMP_RESULT,DiagnosticStatus.INFO,metrics=mapOf(
                        DiagnosticMetric.TIMESTAMP_PRESERVED to if(outcome.timestampPreserved) 1L else 0L))
                    transferDiagnostic.event(DiagnosticStage.COMPLETE,if(verified) DiagnosticStatus.SUCCEEDED else DiagnosticStatus.FAILED,
                        metrics=mapOf(DiagnosticMetric.SOURCE_RETAINED to if(step.method=="COPY" || outcome.status==TransferItemStatus.COMPLETED_SOURCE_RETAINED) 1L else 0L))
                    val stored=step.copy(status=outcome.status.name,destinationUri=outcome.destinationUriString,note=outcome.detailsMessage)
                    db.withTransaction {
                        db.virtualGalleryDao().updatePlan(stored)
                        db.transferLogDao().insertLog(outcome.logEntry)
                        val currentItem=requireNotNull(db.mediaItemDao().getMediaItemById(item.id))
                        db.mediaItemDao().updateMediaItem(currentItem.copy(transferStatus=outcome.status.name,
                            destinationUriString=outcome.destinationUriString ?: item.destinationUriString,
                            timestampVerified=outcome.timestampPreserved,timestampStatusNote=outcome.detailsMessage))
                        db.transferJournalDao().upsert(journal.copy(phase=if(verified) "FINALIZED" else "FAILED",
                            details=outcome.detailsMessage))
                    }
                    done++
                    if(verified) {
                        successful+=stored
                        if(outcome.timestampPreserved) timestampCount++
                        if(step.method=="COPY" || outcome.status==TransferItemStatus.COMPLETED_SOURCE_RETAINED) retained++
                    } else failed++
                    _transferProgress.update { it.copy(processedCount=done,succeededCount=successful.size,
                        failedCount=failed,sourceRetainedCount=retained,timestampVerifiedCount=timestampCount,
                        statusMessage=outcome.detailsMessage) }
                    if(!verified) error("A destination did not verify. Remaining operations stopped; inspect the plan.")
                    } catch(error: Throwable) { transferDiagnostic.failure(error);throw error }
                }
            } catch(cancelled: CancellationException) {
                withContext(NonCancellable) {
                    for(step in steps) if(db.virtualGalleryDao().plans(listOf(step.id)).singleOrNull()?.status=="APPROVED")
                        db.virtualGalleryDao().updatePlan(step.copy(status="CANCELLED",note="Cancelled before this operation started."))
                }
                throw cancelled
            } catch(error: Exception) {
                for(step in steps) if(db.virtualGalleryDao().plans(listOf(step.id)).singleOrNull()?.status=="APPROVED")
                    db.virtualGalleryDao().updatePlan(step.copy(status="CANCELLED",note="Not started. Prior step or plan validation failed."))
                _transferProgress.update { it.copy(statusMessage=error.message.orEmpty()) }
                throw error
            } finally {
                withContext(NonCancellable) { db.virtualGalleryDao().recoverRunningPlans() }
                _transferProgress.update { it.copy(isRunning=false) }
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
