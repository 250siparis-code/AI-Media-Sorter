package com.aimediasorter.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aimediasorter.app.R
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.model.FrameClassificationSample
import com.aimediasorter.app.ui.theme.*
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MediaDetailsScreen(
    item: MediaItemEntity?,
    categories: List<CategoryEntity>,
    onReassignCategory: (Long, CategoryEntity) -> Unit,
    onToggleExclude: (Long, Boolean) -> Unit,
    onBack: () -> Unit
) {
    if (item == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AmoledPureBlack),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = PrimaryPurple)
        }
        return
    }

    var showCategorySheet by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val frameSamples = remember(item.frameBreakdownJson) {
        runCatching {
            Json.decodeFromString<List<FrameClassificationSample>>(item.frameBreakdownJson)
        }.getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPureBlack)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.details_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(14.dp))

        AsyncImage(
            model = item.uriString,
            contentDescription = item.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AmoledCardSurface)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (item.isAmbiguousMultiScene) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = StatusAmber.copy(alpha = 0.16f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StatusAmber)
            ) {
                Text(
                    text = stringResource(R.string.details_ambiguous_video_warning),
                    color = StatusAmber,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Metadata Card
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
                DetailRow(stringResource(R.string.details_filename), item.fileName)
                DetailRow(stringResource(R.string.details_filesize), "${item.fileSizeBytes / 1024} KB (${item.fileSizeBytes} B)")
                DetailRow(stringResource(R.string.details_resolution), "${item.width} x ${item.height}")
                if (item.isVideo) {
                    DetailRow(stringResource(R.string.details_duration), "${item.durationMs / 1000.0} s")
                }
                DetailRow(
                    stringResource(R.string.details_last_modified),
                    dateFormat.format(Date(item.originalLastModifiedMs))
                )
                DetailRow(
                    stringResource(R.string.details_exif_date),
                    item.exifCaptureDateMs?.let { dateFormat.format(Date(it)) }
                        ?: stringResource(R.string.details_exif_none)
                )
                DetailRow(stringResource(R.string.details_detected_category), item.effectiveCategoryName)
                Text(
                    text = stringResource(
                        R.string.details_confidence_metrics,
                        (item.calibratedConfidence * 100).toInt(),
                        item.rawSimilarityScore,
                        item.topMargin
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = PrimaryPurpleLight
                )
                Text(
                    text = stringResource(R.string.details_confidence_note),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (item.primarySubjectNote.isNotBlank()) {
                    Text(
                        text = item.primarySubjectNote,
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusEmerald
                    )
                }
            }
        }

        // Video multi-frame breakdown if video
        if (item.isVideo && frameSamples.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AmoledCardSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AmoledSubtleBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.details_video_frames_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    frameSamples.forEach { sample ->
                        Text(
                            text = "${sample.timestampPercent}% (${sample.timestampMs} ms) → ${sample.categoryName} (${(sample.calibratedConfidence * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Exclude from transfer switch
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AmoledCardSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AmoledSubtleBorder)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.toggle_exclude_transfer),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = item.isExcludedFromTransfer,
                    onCheckedChange = { onToggleExclude(item.id, it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { showCategorySheet = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
        ) {
            Text(stringResource(R.string.btn_change_category))
        }
    }

    if (showCategorySheet) {
        AlertDialog(
            onDismissRequest = { showCategorySheet = false },
            containerColor = AmoledElevatedSurface,
            title = { Text(stringResource(R.string.btn_change_category)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.filter { it.isEnabled }.forEach { cat ->
                        TextButton(
                            onClick = {
                                onReassignCategory(item.id, cat)
                                showCategorySheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${cat.name} (/${cat.destinationSubfolder})",
                                color = if (cat.id == item.effectiveCategoryId) PrimaryPurpleLight else TextPrimaryWhite
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategorySheet = false }) {
                    Text(stringResource(R.string.btn_cancel), color = TextSecondaryMuted)
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.labelMedium)
    }
}
