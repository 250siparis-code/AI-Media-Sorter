package com.aimediasorter.app.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aimediasorter.app.R
import com.aimediasorter.app.data.repository.LiveAnalysisProgress
import com.aimediasorter.app.ui.theme.*

@Composable
fun AnalysisProgressScreen(
    progress: LiveAnalysisProgress,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onViewResults: () -> Unit,
    onExportReport: () -> Unit = {}
) {
    val fraction = if (progress.totalFiles > 0) {
        (progress.processedFiles.toFloat() / progress.totalFiles.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val percentage = (fraction * 100).toInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPureBlack)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.progress_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when {
                progress.errorMessage == "CANCELLED" -> stringResource(R.string.progress_cancelled)
                progress.errorMessage != null -> stringResource(R.string.progress_failed)
                progress.isPaused -> stringResource(R.string.progress_paused)
                progress.isDiscovering -> stringResource(R.string.progress_discovering)
                progress.isRunning -> stringResource(R.string.progress_analyzing)
                progress.processedFiles == 0 -> stringResource(R.string.progress_ready)
                progress.failedCount>0 || progress.pendingCount>0 -> stringResource(R.string.analysis_completed_issues)
                else -> stringResource(R.string.progress_completed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (progress.isPaused) StatusAmber else PrimaryPurpleLight
        )

        Spacer(modifier = Modifier.height(28.dp))

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(168.dp)) {
            if (progress.isDiscovering) {
                CircularProgressIndicator(modifier = Modifier.fillMaxSize(), color = PrimaryPurple, strokeWidth = 12.dp)
            } else CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxSize(),
                color = PrimaryPurple,
                trackColor = AmoledElevatedSurface,
                strokeWidth = 12.dp
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (progress.isDiscovering) "…" else "$percentage%",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.progress_counts_fmt, progress.processedFiles, progress.totalFiles),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondaryMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        AnalysisResultSummary(progress.successfulCount,progress.needsReviewCount,progress.failedCount,
            progress.pendingCount+(progress.totalFiles-progress.processedFiles).coerceAtLeast(0),progress.apiRequestsSent)
        if(!progress.isRunning && progress.processedFiles>0) OutlinedButton(onClick=onExportReport,modifier=Modifier.fillMaxWidth().testTag("progress-export-analysis")) {
            Text(stringResource(R.string.diagnostics_export_batch))
        }
        Spacer(Modifier.height(12.dp))
        // Live file & thumbnail card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AmoledCardSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AmoledSubtleBorder)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (progress.currentPreviewUri.isNotBlank()) {
                    AsyncImage(
                        model = progress.currentPreviewUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AmoledElevatedSurface)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.progress_current_file, progress.currentFileName.ifBlank { "-" }),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.progress_detected_cat, progress.currentDetectedCategory.ifBlank { "-" }),
                        style = MaterialTheme.typography.labelMedium,
                        color = PrimaryPurpleLight
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if(progress.currentDiagnosticCode.isNotEmpty()) AnalysisDiagnosticDetails(
                        progress.currentDiagnosticCode,progress.currentRequestsSent,progress.currentReservations,progress.currentHttpStatus)
                    if(progress.currentIsVideo && progress.currentDiagnosticCode in setOf("SUCCESS","CACHE_HIT")) Text(
                        text = stringResource(R.string.progress_video_sampling),
                        modifier=Modifier.testTag("analysis-video-info"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (progress.errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if(progress.errorMessage=="CANCELLED") stringResource(R.string.progress_cancelled) else progress.errorMessage.orEmpty(),
                color = StatusCoral,
                style = MaterialTheme.typography.labelMedium
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        if (progress.isRunning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onTogglePause,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    border = BorderStroke(1.dp, PrimaryPurple),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (progress.isPaused) {
                            stringResource(R.string.btn_resume)
                        } else {
                            stringResource(R.string.btn_pause)
                        },
                        color = PrimaryPurpleLight
                    )
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    border = BorderStroke(1.dp, StatusCoral),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.btn_cancel_analysis),
                        color = StatusCoral
                    )
                }
            }
        } else {
            Button(
                onClick = onViewResults,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
            ) {
                Text(
                    text = stringResource(R.string.btn_view_results),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }
    }
}
