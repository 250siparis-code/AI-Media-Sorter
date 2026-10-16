package com.aimediasorter.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aimediasorter.app.domain.cloud.*
import com.aimediasorter.app.ui.screens.CloudUiState
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import com.aimediasorter.app.worker.MediaAnalysisWorker
import com.aimediasorter.app.worker.MediaTransferWorker
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.documentfile.provider.DocumentFile

import com.aimediasorter.app.AIMediaSorterApp
import com.aimediasorter.app.data.local.MediaCategoryLink
import com.aimediasorter.app.data.local.PhysicalPlanEntity
import com.aimediasorter.app.data.repository.OperationPreview
import com.aimediasorter.app.data.local.AnalysisSessionEntity
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.local.TransferLogEntity
import com.aimediasorter.app.data.model.AiEngineMode
import com.aimediasorter.app.data.model.CollisionPolicy
import com.aimediasorter.app.data.model.MediaTypeFilter
import com.aimediasorter.app.data.model.OperationMode
import com.aimediasorter.app.data.model.TimestampPolicy
import com.aimediasorter.app.data.model.TransferMethod
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.data.repository.LiveAnalysisProgress
import com.aimediasorter.app.data.repository.LiveTransferProgress
import com.aimediasorter.app.domain.storage.ExportBundleResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as AIMediaSorterApp).repository
    private val settingsRepo = repo.settingsRepository
    private val workManager = WorkManager.getInstance(application)

    val settingsState: StateFlow<AppSettings> = settingsRepo.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettings()
    )

    val categoriesState: StateFlow<List<CategoryEntity>> = repo.categoriesFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val mediaItemsState: StateFlow<List<MediaItemEntity>> = repo.mediaItemsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val galleryLinksState = repo.galleryLinksFlow.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val physicalPlansState = repo.physicalPlansFlow.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val _operationPreview=MutableStateFlow<OperationPreview?>(null)
    val operationPreview=_operationPreview.asStateFlow()
    private var consentIds: Set<Long> = emptySet()
    private var consentForce=false
    fun updateVirtualCategories(ids: Set<Long>,categories: Set<String>,defer: Boolean) { launchSafely {
        repo.updateVirtualCategories(ids,categories,defer)
    } }
    fun requestReanalysis(ids: Set<Long>) {
        if(ids.isEmpty() || ids.size>100 || analysisProgress.value.isRunning) return
        consentIds=ids.toSet();consentForce=true;_cloudConsent.value=true
    }
    fun requestOperationPreview(ids: Set<Long>,method: TransferMethod,moveTargets: Map<Long,String>) { launchSafely {
        require(!transferProgress.value.isRunning) { "Wait for the running operation." }
        _operationPreview.value=repo.previewOperations(ids,method,moveTargets)
    } }
    fun dismissOperationPreview() { _operationPreview.value=null }
    fun confirmOperationPreview() {
        val preview=_operationPreview.value ?: return
        _operationPreview.value=null
        activeTransferJob=launchSafely {
            require(workManager.getWorkInfosForUniqueWorkFlow(MediaTransferWorker.WORK_NAME).first().none { !it.state.isFinished })
            // This call is reachable only from the unchecked-by-default physical confirmation dialog.
            settingsRepo.updateOperationMode(OperationMode.MOVE_FILES)
            val current=settingsRepo.settingsFlow.first()
            val ids=repo.approveOperationPreview(preview)
            val work=OneTimeWorkRequestBuilder<MediaTransferWorker>().setInputData(workDataOf(
                MediaAnalysisWorker.SETTINGS_JSON to Json.encodeToString(current),"physical_plan_ids" to ids.toTypedArray())).build()
            workManager.enqueueUniqueWork(MediaTransferWorker.WORK_NAME,ExistingWorkPolicy.KEEP,work)
        }
    }

    val transferLogsState: StateFlow<List<TransferLogEntity>> = repo.transferLogsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val sessionsState: StateFlow<List<AnalysisSessionEntity>> = repo.sessionsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val analysisProgress: StateFlow<LiveAnalysisProgress> =
        combine(repo.analysisProgress, workManager.getWorkInfosForUniqueWorkFlow(MediaAnalysisWorker.WORK_NAME), settingsRepo.settingsFlow) { progress, work, settings ->
            progress.copy(isPaused = settings.analysisPaused && work.any { !it.state.isFinished }, isRunning = work.any { !it.state.isFinished },
                errorMessage = progress.errorMessage ?: if (work.singleOrNull()?.state == androidx.work.WorkInfo.State.FAILED) "Analysis failed; inspect permissions and settings." else null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LiveAnalysisProgress())

    val transferProgress: StateFlow<LiveTransferProgress> =
        combine(repo.transferProgress, workManager.getWorkInfosForUniqueWorkFlow(MediaTransferWorker.WORK_NAME)) { progress, work ->
            progress.copy(isRunning = work.any { !it.state.isFinished })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LiveTransferProgress())

    val folderLoopError: StateFlow<Boolean> = settingsRepo.settingsFlow.map {
        withContext(Dispatchers.IO) { repo.storageScanner.isDestinationInsideSource(it.sourceUri, it.destinationUri) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)


    private val cloudSettingsMutex=Mutex()
    private fun launchCloud(block: suspend ()->Unit)=launchSafely { cloudSettingsMutex.withLock { block() } }

    private val _cloudConsent = MutableStateFlow(false)
    val cloudConsent = _cloudConsent.asStateFlow()
    private val _cloudUi = MutableStateFlow(CloudUiState())
    val cloudUi = _cloudUi.asStateFlow()
    fun refreshCloudLocalState() { launchCloud {
        _cloudUi.value = _cloudUi.value.copy(hasKey=repo.cloud.vault.hasKey(),
            models=repo.cloud.state.models(repo.cloud.vault.revision), usage=repo.cloud.state.usage(),
            modelVerified=repo.cloud.state.modelVerified(repo.cloud.vault.revision,settingsRepo.settingsFlow.first().cloudOptions.model))
    } }
    fun setCloudOptions(options: CloudOptions) { launchCloud {
        options.validate()
        repo.cloud.state.revokeAll()
        workManager.cancelUniqueWork(MediaAnalysisWorker.WORK_NAME)
        settingsRepo.updateCloudOptions(options.copy(mode=AnalysisMode.GEMINI))
        _cloudUi.value=_cloudUi.value.copy(message="MODEL_SAVED",failure=null,error=null,
            modelVerified=repo.cloud.state.modelVerified(repo.cloud.vault.revision,options.model))
    } }
    fun saveGeminiKey(value: String) { launchCloud {
        if (_cloudUi.value.busy) return@launchCloud
        _cloudUi.value=_cloudUi.value.copy(busy=true,error=null,message=null,failure=null)
        try {
            workManager.cancelUniqueWork(MediaAnalysisWorker.WORK_NAME)
            repo.cloud.state.revokeAll()
            repo.cloud.vault.save(value)
            check(repo.cloud.vault.read()==ApiKeyInput.normalized(value))
            _cloudUi.value=_cloudUi.value.copy(hasKey=true,models=repo.cloud.state.models(repo.cloud.vault.revision),
                modelVerified=false,message="SAVED",savedRevision=_cloudUi.value.savedRevision+1)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _cloudUi.value=_cloudUi.value.copy(error="SAVE") }
        finally { _cloudUi.value=_cloudUi.value.copy(busy=false) }
    } }
    fun clearGeminiKey() { launchCloud {
        if (_cloudUi.value.busy) return@launchCloud
        _cloudUi.value=_cloudUi.value.copy(busy=true,error=null,message=null,failure=null)
        try {
            workManager.cancelUniqueWork(MediaAnalysisWorker.WORK_NAME)
            repo.cloud.state.revokeAll();repo.cloud.vault.clear()
            _cloudUi.value=_cloudUi.value.copy(hasKey=false,models=emptyList(),modelVerified=false,message="DELETED")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _cloudUi.value=_cloudUi.value.copy(error="DELETE") }
        finally { _cloudUi.value=_cloudUi.value.copy(busy=false) }
    } }
    fun refreshGeminiModels() { launchCloud {
        _cloudUi.value = _cloudUi.value.copy(busy=true,error=null,message=null,failure=null)
        try {
            val models=repo.cloud.refreshModels()
            _cloudUi.value = _cloudUi.value.copy(models=models,hasKey=true,message=if(models.isEmpty()) null else "CONNECTED",failure=if(models.isEmpty()) CloudErrorKind.MODEL else null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (known: CloudFailure) { _cloudUi.value=_cloudUi.value.copy(failure=known.kind) }
        catch (_: Exception) { _cloudUi.value=_cloudUi.value.copy(failure=CloudErrorKind.RESPONSE) }
        finally { _cloudUi.value=_cloudUi.value.copy(busy=false) }
    } }
    fun testGeminiModel() { launchCloud {
        if(_cloudUi.value.busy) return@launchCloud
        _cloudUi.value=_cloudUi.value.copy(busy=true,error=null,failure=null,message=null,modelVerified=false)
        try {
            repo.cloud.testSelectedModel(settingsRepo.settingsFlow.first().cloudOptions)
            _cloudUi.value=_cloudUi.value.copy(message="MODEL_OK",usage=repo.cloud.state.usage(),modelVerified=true)
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: CloudFailure) { _cloudUi.value=_cloudUi.value.copy(failure=error.kind) }
        catch(_: Exception) { _cloudUi.value=_cloudUi.value.copy(failure=CloudErrorKind.RESPONSE) }
        finally { _cloudUi.value=_cloudUi.value.copy(busy=false) }
    } }
    fun dismissCloudConsent() { _cloudConsent.value=false }
    fun approveCloudBatch() { launchSafely {
        val settings=settingsRepo.settingsFlow.first()
        val ids=consentIds.toSet();val force=consentForce
        val batch=repo.authorizeCloudBatch(settings,ids,force)
        _cloudConsent.value=false
        enqueueAnalysis(settings,batch,ids,force)
    } }
    private fun enqueueAnalysis(settings: AppSettings,batch: String = "",ids: Set<Long> = emptySet(),force: Boolean=false) {
        val work=OneTimeWorkRequestBuilder<MediaAnalysisWorker>()
            .setInputData(workDataOf(MediaAnalysisWorker.SETTINGS_JSON to Json.encodeToString(settings),
                "cloud_batch_id" to batch,"selected_media_ids" to ids.toLongArray(),"force_reanalysis" to force)).build()
        workManager.enqueueUniqueWork(MediaAnalysisWorker.WORK_NAME,ExistingWorkPolicy.KEEP,work)
    }

    private val _exportStatusMessage = MutableStateFlow<String?>(null)
    val exportStatusMessage: StateFlow<String?> = _exportStatusMessage.asStateFlow()

    private val _lastTermuxExportResult = MutableStateFlow<ExportBundleResult?>(null)
    val lastTermuxExportResult: StateFlow<ExportBundleResult?> = _lastTermuxExportResult.asStateFlow()

    private var activeAnalysisJob: Job? = null
    private var activeTransferJob: Job? = null

    init {
        launchSafely {
            repo.ensureDefaultCategories()
            repo.reconcileInterruptedTransfers()
            refreshCloudLocalState()
        }
    }

    fun selectSourceFolder(uri: Uri) {
        launchSafely {
            try {
                persistUriPermission(uri, false)
                workManager.cancelUniqueWork(MediaAnalysisWorker.WORK_NAME)
                workManager.cancelUniqueWork(MediaTransferWorker.WORK_NAME)
                settingsRepo.updateSourceUri(uri.toString())
            } catch (error: Exception) {
                _exportStatusMessage.value = "ERROR:" + error.message
            }
        }
    }

    fun selectDestinationFolder(uri: Uri) {
        launchSafely {
            try {
                persistUriPermission(uri, true)
                workManager.cancelUniqueWork(MediaTransferWorker.WORK_NAME)
                settingsRepo.updateDestinationUri(uri.toString())
            } catch (error: Exception) {
                _exportStatusMessage.value = "ERROR:" + error.message
            }
        }
    }

    fun isFolderSelectionLoop(sourceUri: String, destUri: String): Boolean {
        return repo.storageScanner.isDestinationInsideSource(sourceUri, destUri)
    }

    fun setIncludeSubfolders(enabled: Boolean) {
        launchSafely { settingsRepo.updateIncludeSubfolders(enabled) }
    }

    fun setMediaTypeFilter(filter: MediaTypeFilter) {
        launchSafely { settingsRepo.updateMediaTypeFilter(filter) }
    }

    fun toggleSupportedExtension(ext: String) {
        launchSafely { settingsRepo.toggleExtension(ext) }
    }

    fun setOperationMode(mode: OperationMode) {
        launchSafely {
            settingsRepo.updateOperationMode(mode)
            if (mode == OperationMode.ANALYSIS_ONLY) workManager.cancelUniqueWork(MediaTransferWorker.WORK_NAME)
        }
    }

    fun setTransferMethod(method: TransferMethod) {
        launchSafely { settingsRepo.updateTransferMethod(method) }
    }

    fun setConfidenceThreshold(value: Float) {
        launchSafely { settingsRepo.updateConfidenceThreshold(value) }
    }

    fun setAiEngineMode(mode: AiEngineMode) {
        launchSafely { settingsRepo.updateAiEngineMode(mode) }
    }

    fun setCustomOnnxModelPath(path: String) {
        launchSafely { settingsRepo.updateCustomOnnxModelPath(path) }
    }

    fun setHardwareAcceleration(enabled: Boolean) {
        launchSafely { settingsRepo.updateHardwareAcceleration(enabled) }
    }

    fun setTimestampPolicy(policy: TimestampPolicy) {
        launchSafely { settingsRepo.updateTimestampPolicy(policy) }
    }

    fun setCollisionPolicy(policy: CollisionPolicy) {
        launchSafely { settingsRepo.updateCollisionPolicy(policy) }
    }

    fun setPreviewBeforeMoving(enabled: Boolean) {
        launchSafely { settingsRepo.updatePreviewBeforeMoving(enabled) }
    }

    fun setLanguage(langCode: String) {
        launchSafely {
            settingsRepo.updateLanguageCode(langCode)
            val appLocale = LocaleListCompat.forLanguageTags(langCode)
            withContext(Dispatchers.Main.immediate) { AppCompatDelegate.setApplicationLocales(appLocale) }
        }
    }

    fun setPureBlackAmoled(enabled: Boolean) {
        launchSafely { settingsRepo.updatePureBlackAmoled(enabled) }
    }

    fun toggleCategoryEnabled(category: CategoryEntity) {
        launchSafely {
            repo.upsertCategory(category.copy(isEnabled = !category.isEnabled))
        }
    }

    fun saveCategory(
        existingId: String?,
        name: String,
        description: String,
        subfolder: String,
        priority: Int,
        colorHex: Long = 0xFF8B45FF
    ) {
        if (name.isBlank()) return
        launchSafely {
            val id = existingId ?: "cat_custom_${UUID.randomUUID().toString().take(8)}"
            val existing = categoriesState.value.firstOrNull { it.id == id }
            val entity = CategoryEntity(
                id = id,
                name = name.trim(),
                description = description.trim(),
                colorHex = existing?.colorHex ?: colorHex,
                iconKey = existing?.iconKey ?: "label",
                priority = priority.coerceIn(1, 10),
                isEnabled = existing?.isEnabled ?: true,
                isDefault = existing?.isDefault ?: false,
                destinationSubfolder = subfolder.trim().ifBlank { name.trim() },
                customDestinationUri = existing?.customDestinationUri
            )
            repo.upsertCategory(entity)
        }
    }

    fun deleteCustomCategory(categoryId: String) {
        launchSafely {
            repo.deleteCustomCategory(categoryId)
        }
    }

    fun startAnalysis() {
        if (analysisProgress.value.isRunning || activeAnalysisJob?.isActive == true) return
        activeAnalysisJob = launchSafely {
            repo.setAnalysisPaused(false)
            val settings = settingsRepo.settingsFlow.first()
            consentIds=emptySet();consentForce=false
            _cloudConsent.value=true
        }
    }

    fun togglePauseAnalysis() {
        val currentPaused = analysisProgress.value.isPaused
        launchSafely { repo.setAnalysisPaused(!currentPaused) }
    }

    fun cancelAnalysis() {
        workManager.cancelUniqueWork(MediaAnalysisWorker.WORK_NAME)
        launchSafely { repo.cloud.state.revokeAll(); repo.setAnalysisPaused(false); refreshCloudLocalState() }
    }

    fun reassignMediaCategory(itemId: Long, category: CategoryEntity) {
        launchSafely {
            repo.reassignMediaCategory(itemId, category)
        }
    }

    fun setExcludeMediaItem(itemId: Long, excluded: Boolean) {
        launchSafely {
            repo.setExcludeFromTransfer(itemId, excluded)
        }
    }

    fun executeTransfers(includeReviewItems: Boolean = false) {
        if (transferProgress.value.isRunning || activeTransferJob?.isActive == true) return
        activeTransferJob = launchSafely {
            val settings = settingsRepo.settingsFlow.first()
            if (settings.operationMode != OperationMode.MOVE_FILES) {
                _exportStatusMessage.value = "ORGANIZE_REQUIRED"
                return@launchSafely
            }
            val work = OneTimeWorkRequestBuilder<MediaTransferWorker>()
                .setInputData(workDataOf(MediaAnalysisWorker.SETTINGS_JSON to Json.encodeToString(settings),
                    MediaTransferWorker.INCLUDE_REVIEW to includeReviewItems)).build()
            workManager.enqueueUniqueWork(MediaTransferWorker.WORK_NAME, ExistingWorkPolicy.KEEP, work)
        }
    }

    fun cancelTransfers() {
        workManager.cancelUniqueWork(MediaTransferWorker.WORK_NAME)
    }

    fun saveLastExport(uri: Uri) {
        val result = _lastTermuxExportResult.value ?: return
        launchSafely {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    ZipOutputStream(output).use { zip ->
                        result.generatedFiles.forEach { file ->
                            zip.putNextEntry(ZipEntry(file.name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                } ?: error("Export destination unavailable.")
                _exportStatusMessage.value = "EXPORT_SAVED"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _exportStatusMessage.value = "ERROR:" + error.message }
        }
    }

    fun exportOperationsCsv(selectedIds: Set<Long>) {
        launchSafely {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxCsv(selectedIds, s.destinationUri, s.transferMethod)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = "EXPORT_READY"
        }
    }

    fun exportOperationsJson(selectedIds: Set<Long>) {
        launchSafely {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxJson(selectedIds, s.destinationUri, s.transferMethod)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = "EXPORT_READY"
        }
    }

    fun exportOperationsMoveScript(selectedIds: Set<Long>) {
        launchSafely {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxScript(selectedIds, s.destinationUri, TransferMethod.MOVE)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = "EXPORT_READY"
        }
    }

    fun exportOperationsCopyScript(selectedIds: Set<Long>) {
        launchSafely {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxScript(selectedIds, s.destinationUri, TransferMethod.COPY)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = "EXPORT_READY"
        }
    }

    fun exportOperationsAll(selectedIds: Set<Long>) {
        launchSafely {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxAll(selectedIds, s.destinationUri, s.transferMethod)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = "EXPORT_READY"
        }
    }

    fun exportCsvReport() {
        launchSafely {
            val file = repo.exportCsv()
            _lastTermuxExportResult.value = ExportBundleResult(file.parent.orEmpty(), mediaItemsState.value.size, 0, listOf(file))
            _exportStatusMessage.value = "EXPORT_READY"
        }
    }

    fun exportJsonReport() {
        launchSafely {
            val file = repo.exportJson()
            _lastTermuxExportResult.value = ExportBundleResult(file.parent.orEmpty(), mediaItemsState.value.size, 0, listOf(file))
            _exportStatusMessage.value = "EXPORT_READY"
        }
    }

    fun dismissStatusMessage() { _exportStatusMessage.value = null }

    private fun launchSafely(block: suspend () -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _exportStatusMessage.value = "ERROR:" + error.message }
        }

    private fun persistUriPermission(uri: Uri, requireWrite: Boolean) {
        val resolver = getApplication<Application>().contentResolver
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val document = DocumentFile.fromTreeUri(getApplication(), uri) ?: error("Invalid folder.")
        require(document.canRead()) { "Read permission was not granted." }
        if (document.canWrite()) {
            resolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } else {
            require(!requireWrite) { "Destination write permission was not granted." }
        }
        require(resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && (!requireWrite || it.isWritePermission)
        }) { "Persistent folder permission was not granted." }
    }
}
