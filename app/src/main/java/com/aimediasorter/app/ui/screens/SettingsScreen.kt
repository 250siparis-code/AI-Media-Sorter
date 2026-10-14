package com.aimediasorter.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aimediasorter.app.R
import com.aimediasorter.app.data.local.AnalysisSessionEntity
import com.aimediasorter.app.data.model.AiEngineMode
import com.aimediasorter.app.data.model.CollisionPolicy
import com.aimediasorter.app.data.model.TimestampPolicy
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.ui.theme.*

@Composable
fun SettingsScreen(
    settings: AppSettings,
    sessions: List<AnalysisSessionEntity>,
    exportStatusMessage: String?,
    onLanguageChange: (String) -> Unit,
    onAiEngineChange: (AiEngineMode) -> Unit,
    onHardwareAccelChange: (Boolean) -> Unit,
    onConfidenceChange: (Float) -> Unit,
    onTimestampPolicyChange: (TimestampPolicy) -> Unit,
    onCollisionPolicyChange: (CollisionPolicy) -> Unit,
    onPreviewBeforeMovingChange: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPureBlack)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium
        )

        // Section 1: Language & AMOLED
        SettingsSectionCard(title = stringResource(R.string.settings_section_appearance)) {
            Text(text = stringResource(R.string.settings_language), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.languageCode == "en",
                    onClick = { onLanguageChange("en") },
                    label = { Text(stringResource(R.string.lang_english)) }
                )
                FilterChip(
                    selected = settings.languageCode == "tr",
                    onClick = { onLanguageChange("tr") },
                    label = { Text(stringResource(R.string.lang_turkish)) }
                )
            }
        }

        // Section 2: On-Device AI Model & Threshold
        SettingsSectionCard(title = stringResource(R.string.settings_section_ai)) {
            Text(text = stringResource(R.string.settings_model_info), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))

            AiEngineMode.entries.forEach { mode ->
                val labelRes = when (mode) {
                    AiEngineMode.HYBRID_ONNX_AND_MLKIT -> R.string.ai_engine_hybrid
                    AiEngineMode.ONNX_EMBEDDER_ONLY -> R.string.ai_engine_onnx_only
                    AiEngineMode.CUSTOM_MOBILECLIP_ONNX -> R.string.ai_engine_custom_clip
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = settings.aiEngineMode == mode,
                        onClick = { onAiEngineChange(mode) }
                    )
                    Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_hw_accel),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.hardwareAcceleration,
                    onCheckedChange = onHardwareAccelChange
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_confidence_threshold, (settings.confidenceThreshold * 100).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = PrimaryPurpleLight
            )
            Slider(
                value = settings.confidenceThreshold,
                onValueChange = onConfidenceChange,
                valueRange = 0.30f..0.90f
            )
        }

        // Section 3: Timestamp & Collision Policies
        SettingsSectionCard(title = stringResource(R.string.settings_section_safety)) {
            Text(text = stringResource(R.string.settings_timestamp_policy), style = MaterialTheme.typography.labelMedium)
            TimestampPolicy.entries.forEach { policy ->
                val res = when (policy) {
                    TimestampPolicy.BEST_EFFORT_WITH_REPORT -> R.string.ts_policy_best_effort
                    TimestampPolicy.FALLBACK_COPY_KEEP_ORIGINAL -> R.string.ts_policy_fallback_copy
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = settings.timestampPolicy == policy,
                        onClick = { onTimestampPolicyChange(policy) }
                    )
                    Text(text = stringResource(res), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = stringResource(R.string.settings_collision_policy), style = MaterialTheme.typography.labelMedium)
            CollisionPolicy.entries.forEach { policy ->
                val res = when (policy) {
                    CollisionPolicy.RENAME_WITH_SUFFIX -> R.string.collision_rename
                    CollisionPolicy.SKIP_EXISTING -> R.string.collision_skip
                    CollisionPolicy.VERIFY_HASH_AND_SKIP -> R.string.collision_verify_hash
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = settings.collisionPolicy == policy,
                        onClick = { onCollisionPolicyChange(policy) }
                    )
                    Text(text = stringResource(res), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_preview_before_move),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.previewBeforeMoving,
                    onCheckedChange = onPreviewBeforeMovingChange
                )
            }
        }

        // Section 4: History & Export
        SettingsSectionCard(title = stringResource(R.string.settings_section_history)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onExportCsv,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, PrimaryPurple)
                ) {
                    Text(stringResource(R.string.btn_export_csv), color = PrimaryPurpleLight)
                }
                OutlinedButton(
                    onClick = onExportJson,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, PrimaryPurple)
                ) {
                    Text(stringResource(R.string.btn_export_json), color = PrimaryPurpleLight)
                }
            }

            if (!exportStatusMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.export_success_fmt, exportStatusMessage),
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusEmerald
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (sessions.isEmpty()) {
                Text(text = stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                sessions.take(5).forEach { s ->
                    Text(
                        text = "• [${s.status}] ${s.totalClassified}/${s.totalScanned} files (Uncertain: ${s.uncertainCount})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AmoledCardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AmoledSubtleBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = PrimaryPurpleLight)
            content()
        }
    }
}
