package com.aimediasorter.app.data.preferences

import android.content.Context
import kotlinx.serialization.Serializable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aimediasorter.app.data.model.AiEngineMode
import com.aimediasorter.app.data.model.CollisionPolicy
import com.aimediasorter.app.data.model.DefaultCategories
import com.aimediasorter.app.data.model.MediaTypeFilter
import com.aimediasorter.app.data.model.OperationMode
import com.aimediasorter.app.data.model.TimestampPolicy
import com.aimediasorter.app.data.model.TransferMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_media_sorter_settings")

@Serializable
data class AppSettings(
    val analysisPaused: Boolean = false,
    val sourceUri: String = "",
    val destinationUri: String = "",
    val includeSubfolders: Boolean = true,
    val mediaTypeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    val enabledExtensions: Set<String> = DefaultCategories.ALL_SUPPORTED_EXTENSIONS,
    val operationMode: OperationMode = OperationMode.ANALYSIS_ONLY, // Default safe mode
    val transferMethod: TransferMethod = TransferMethod.COPY,
    val confidenceThreshold: Float = 0.60f,
    val aiEngineMode: AiEngineMode = AiEngineMode.HYBRID_ONNX_AND_MLKIT,
    val customOnnxModelPath: String = "",
    val hardwareAcceleration: Boolean = true,
    val timestampPolicy: TimestampPolicy = TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL,
    val collisionPolicy: CollisionPolicy = CollisionPolicy.RENAME_WITH_SUFFIX,
    val previewBeforeMoving: Boolean = true,
    val languageCode: String = "en",
    val pureBlackAmoled: Boolean = true
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ANALYSIS_PAUSED = booleanPreferencesKey("analysis_paused")
        val SOURCE_URI = stringPreferencesKey("source_uri")
        val DESTINATION_URI = stringPreferencesKey("destination_uri")
        val INCLUDE_SUBFOLDERS = booleanPreferencesKey("include_subfolders")
        val MEDIA_TYPE_FILTER = stringPreferencesKey("media_type_filter")
        val ENABLED_EXTENSIONS = stringSetPreferencesKey("enabled_extensions")
        val OPERATION_MODE = stringPreferencesKey("operation_mode")
        val TRANSFER_METHOD = stringPreferencesKey("transfer_method")
        val CONFIDENCE_THRESHOLD = floatPreferencesKey("confidence_threshold")
        val AI_ENGINE_MODE = stringPreferencesKey("ai_engine_mode")
        val CUSTOM_ONNX_PATH = stringPreferencesKey("custom_onnx_path")
        val HARDWARE_ACCEL = booleanPreferencesKey("hardware_accel")
        val TIMESTAMP_POLICY = stringPreferencesKey("timestamp_policy")
        val COLLISION_POLICY = stringPreferencesKey("collision_policy")
        val PREVIEW_BEFORE_MOVING = booleanPreferencesKey("preview_before_moving")
        val LANGUAGE_CODE = stringPreferencesKey("language_code")
        val PURE_BLACK_AMOLED = booleanPreferencesKey("pure_black_amoled")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            analysisPaused = prefs[Keys.ANALYSIS_PAUSED] ?: false,
            sourceUri = prefs[Keys.SOURCE_URI] ?: "",
            destinationUri = prefs[Keys.DESTINATION_URI] ?: "",
            includeSubfolders = prefs[Keys.INCLUDE_SUBFOLDERS] ?: true,
            mediaTypeFilter = runCatching {
                MediaTypeFilter.valueOf(prefs[Keys.MEDIA_TYPE_FILTER] ?: MediaTypeFilter.ALL.name)
            }.getOrDefault(MediaTypeFilter.ALL),
            enabledExtensions = prefs[Keys.ENABLED_EXTENSIONS] ?: DefaultCategories.ALL_SUPPORTED_EXTENSIONS,
            operationMode = runCatching {
                OperationMode.valueOf(prefs[Keys.OPERATION_MODE] ?: OperationMode.ANALYSIS_ONLY.name)
            }.getOrDefault(OperationMode.ANALYSIS_ONLY),
            transferMethod = runCatching {
                TransferMethod.valueOf(prefs[Keys.TRANSFER_METHOD] ?: TransferMethod.COPY.name)
            }.getOrDefault(TransferMethod.COPY),
            confidenceThreshold = prefs[Keys.CONFIDENCE_THRESHOLD] ?: 0.60f,
            aiEngineMode = runCatching {
                AiEngineMode.valueOf(prefs[Keys.AI_ENGINE_MODE] ?: AiEngineMode.HYBRID_ONNX_AND_MLKIT.name)
            }.getOrDefault(AiEngineMode.HYBRID_ONNX_AND_MLKIT).let {
                if (it == AiEngineMode.CUSTOM_MOBILECLIP_ONNX) AiEngineMode.HYBRID_ONNX_AND_MLKIT else it
            },
            customOnnxModelPath = prefs[Keys.CUSTOM_ONNX_PATH] ?: "",
            hardwareAcceleration = prefs[Keys.HARDWARE_ACCEL] ?: true,
            timestampPolicy = runCatching {
                TimestampPolicy.valueOf(prefs[Keys.TIMESTAMP_POLICY] ?: TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL.name)
            }.getOrDefault(TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL),
            collisionPolicy = runCatching {
                CollisionPolicy.valueOf(prefs[Keys.COLLISION_POLICY] ?: CollisionPolicy.RENAME_WITH_SUFFIX.name)
            }.getOrDefault(CollisionPolicy.RENAME_WITH_SUFFIX),
            previewBeforeMoving = prefs[Keys.PREVIEW_BEFORE_MOVING] ?: true,
            languageCode = prefs[Keys.LANGUAGE_CODE] ?: "en",
            pureBlackAmoled = prefs[Keys.PURE_BLACK_AMOLED] ?: true
        )
    }

    suspend fun updateAnalysisPaused(paused: Boolean) = context.dataStore.edit { it[Keys.ANALYSIS_PAUSED] = paused }
    suspend fun updateSourceUri(uri: String) = context.dataStore.edit { it[Keys.SOURCE_URI] = uri }
    suspend fun updateDestinationUri(uri: String) = context.dataStore.edit { it[Keys.DESTINATION_URI] = uri }
    suspend fun updateIncludeSubfolders(enabled: Boolean) = context.dataStore.edit { it[Keys.INCLUDE_SUBFOLDERS] = enabled }
    suspend fun updateMediaTypeFilter(filter: MediaTypeFilter) = context.dataStore.edit { it[Keys.MEDIA_TYPE_FILTER] = filter.name }
    suspend fun toggleExtension(ext: String) = context.dataStore.edit { prefs ->
        val current = (prefs[Keys.ENABLED_EXTENSIONS] ?: DefaultCategories.ALL_SUPPORTED_EXTENSIONS).toMutableSet()
        val normalized = ext.lowercase()
        if (current.contains(normalized)) {
            current.remove(normalized)
        } else {
            current.add(normalized)
        }
        prefs[Keys.ENABLED_EXTENSIONS] = current
    }
    suspend fun updateOperationMode(mode: OperationMode) = context.dataStore.edit { it[Keys.OPERATION_MODE] = mode.name }
    suspend fun updateTransferMethod(method: TransferMethod) = context.dataStore.edit { it[Keys.TRANSFER_METHOD] = method.name }
    suspend fun updateConfidenceThreshold(threshold: Float) = context.dataStore.edit { it[Keys.CONFIDENCE_THRESHOLD] = threshold.coerceIn(0.25f, 0.95f) }
    suspend fun updateAiEngineMode(mode: AiEngineMode) = context.dataStore.edit { it[Keys.AI_ENGINE_MODE] = mode.name }
    suspend fun updateCustomOnnxModelPath(path: String) = context.dataStore.edit { it[Keys.CUSTOM_ONNX_PATH] = path }
    suspend fun updateHardwareAcceleration(enabled: Boolean) = context.dataStore.edit { it[Keys.HARDWARE_ACCEL] = enabled }
    suspend fun updateTimestampPolicy(policy: TimestampPolicy) = context.dataStore.edit { it[Keys.TIMESTAMP_POLICY] = policy.name }
    suspend fun updateCollisionPolicy(policy: CollisionPolicy) = context.dataStore.edit { it[Keys.COLLISION_POLICY] = policy.name }
    suspend fun updatePreviewBeforeMoving(enabled: Boolean) = context.dataStore.edit { it[Keys.PREVIEW_BEFORE_MOVING] = enabled }
    suspend fun updateLanguageCode(lang: String) = context.dataStore.edit { it[Keys.LANGUAGE_CODE] = lang }
    suspend fun updatePureBlackAmoled(enabled: Boolean) = context.dataStore.edit { it[Keys.PURE_BLACK_AMOLED] = enabled }
}
