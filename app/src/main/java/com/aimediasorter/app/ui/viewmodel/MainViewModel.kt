package com.aimediasorter.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aimediasorter.app.AIMediaSorterApp
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

    val analysisProgress: StateFlow<LiveAnalysisProgress> = repo.analysisProgress
    val transferProgress: StateFlow<LiveTransferProgress> = repo.transferProgress

    private val _exportStatusMessage = MutableStateFlow<String?>(null)
    val exportStatusMessage: StateFlow<String?> = _exportStatusMessage.asStateFlow()

    private val _lastTermuxExportResult = MutableStateFlow<ExportBundleResult?>(null)
    val lastTermuxExportResult: StateFlow<ExportBundleResult?> = _lastTermuxExportResult.asStateFlow()

    private var activeAnalysisJob: Job? = null
    private var activeTransferJob: Job? = null

    init {
        viewModelScope.launch {
            repo.ensureDefaultCategories()
        }
    }

    fun selectSourceFolder(uri: Uri) {
        persistUriPermission(uri)
        viewModelScope.launch {
            settingsRepo.updateSourceUri(uri.toString())
        }
    }

    fun selectDestinationFolder(uri: Uri) {
        persistUriPermission(uri)
        viewModelScope.launch {
            settingsRepo.updateDestinationUri(uri.toString())
        }
    }

    fun isFolderSelectionLoop(sourceUri: String, destUri: String): Boolean {
        return repo.storageScanner.isDestinationInsideSource(sourceUri, destUri)
    }

    fun setIncludeSubfolders(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateIncludeSubfolders(enabled) }
    }

    fun setMediaTypeFilter(filter: MediaTypeFilter) {
        viewModelScope.launch { settingsRepo.updateMediaTypeFilter(filter) }
    }

    fun toggleSupportedExtension(ext: String) {
        viewModelScope.launch { settingsRepo.toggleExtension(ext) }
    }

    fun setOperationMode(mode: OperationMode) {
        viewModelScope.launch { settingsRepo.updateOperationMode(mode) }
    }

    fun setTransferMethod(method: TransferMethod) {
        viewModelScope.launch { settingsRepo.updateTransferMethod(method) }
    }

    fun setConfidenceThreshold(value: Float) {
        viewModelScope.launch { settingsRepo.updateConfidenceThreshold(value) }
    }

    fun setAiEngineMode(mode: AiEngineMode) {
        viewModelScope.launch { settingsRepo.updateAiEngineMode(mode) }
    }

    fun setCustomOnnxModelPath(path: String) {
        viewModelScope.launch { settingsRepo.updateCustomOnnxModelPath(path) }
    }

    fun setHardwareAcceleration(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateHardwareAcceleration(enabled) }
    }

    fun setTimestampPolicy(policy: TimestampPolicy) {
        viewModelScope.launch { settingsRepo.updateTimestampPolicy(policy) }
    }

    fun setCollisionPolicy(policy: CollisionPolicy) {
        viewModelScope.launch { settingsRepo.updateCollisionPolicy(policy) }
    }

    fun setPreviewBeforeMoving(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updatePreviewBeforeMoving(enabled) }
    }

    fun setLanguage(langCode: String) {
        viewModelScope.launch {
            settingsRepo.updateLanguageCode(langCode)
            val appLocale = LocaleListCompat.forLanguageTags(langCode)
            AppCompatDelegate.setApplicationLocales(appLocale)
        }
    }

    fun setPureBlackAmoled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updatePureBlackAmoled(enabled) }
    }

    fun toggleCategoryEnabled(category: CategoryEntity) {
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            repo.deleteCustomCategory(categoryId)
        }
    }

    fun startAnalysis() {
        if (activeAnalysisJob?.isActive == true) return
        activeAnalysisJob = viewModelScope.launch {
            val currentSettings = settingsRepo.settingsFlow.first()
            runCatching {
                repo.executeMediaScanAndAnalysis(currentSettings)
            }
        }
    }

    fun togglePauseAnalysis() {
        val currentPaused = analysisProgress.value.isPaused
        repo.setAnalysisPaused(!currentPaused)
    }

    fun cancelAnalysis() {
        activeAnalysisJob?.cancel()
        repo.setAnalysisPaused(false)
    }

    fun reassignMediaCategory(itemId: Long, category: CategoryEntity) {
        viewModelScope.launch {
            repo.reassignMediaCategory(itemId, category)
        }
    }

    fun setExcludeMediaItem(itemId: Long, excluded: Boolean) {
        viewModelScope.launch {
            repo.setExcludeFromTransfer(itemId, excluded)
        }
    }

    fun executeTransfers(includeReviewItems: Boolean = false) {
        if (activeTransferJob?.isActive == true) return
        activeTransferJob = viewModelScope.launch {
            val currentSettings = settingsRepo.settingsFlow.first()
            repo.executeVerifiedTransfers(currentSettings, includeReviewItems)
        }
    }

    fun cancelTransfers() {
        activeTransferJob?.cancel()
    }


    fun exportOperationsCsv(selectedIds: Set<Long>) {
        viewModelScope.launch {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxCsv(selectedIds, s.destinationUri, s.transferMethod)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = res.exportDirectoryPath
        }
    }

    fun exportOperationsJson(selectedIds: Set<Long>) {
        viewModelScope.launch {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxJson(selectedIds, s.destinationUri, s.transferMethod)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = res.exportDirectoryPath
        }
    }

    fun exportOperationsMoveScript(selectedIds: Set<Long>) {
        viewModelScope.launch {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxScript(selectedIds, s.destinationUri, TransferMethod.MOVE)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = res.exportDirectoryPath
        }
    }

    fun exportOperationsCopyScript(selectedIds: Set<Long>) {
        viewModelScope.launch {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxScript(selectedIds, s.destinationUri, TransferMethod.COPY)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = res.exportDirectoryPath
        }
    }

    fun exportOperationsAll(selectedIds: Set<Long>) {
        viewModelScope.launch {
            val s = settingsRepo.settingsFlow.first()
            val res = repo.exportTermuxAll(selectedIds, s.destinationUri, s.transferMethod)
            _lastTermuxExportResult.value = res
            _exportStatusMessage.value = res.exportDirectoryPath
        }
    }

    fun exportCsvReport() {
        viewModelScope.launch {
            val file = repo.exportCsv()
            _exportStatusMessage.value = file.absolutePath
        }
    }

    fun exportJsonReport() {
        viewModelScope.launch {
            val file = repo.exportJson()
            _exportStatusMessage.value = file.absolutePath
        }
    }

    private fun persistUriPermission(uri: Uri) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
        }
    }
}
