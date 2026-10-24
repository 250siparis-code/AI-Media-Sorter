package com.aimediasorter.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aimediasorter.app.R
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.domain.storage.ExportBundleResult
import com.aimediasorter.app.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisResultsScreen(
    mediaItems: List<MediaItemEntity>,
    categories: List<CategoryEntity>,
    lastExportResult: ExportBundleResult?,
    onSaveExport: (Uri) -> Unit,
    isAnalysisRunning: Boolean,
    onInspectItem: (Long) -> Unit,
    onQuickReassignCategory: (Long, CategoryEntity) -> Unit,
    onExportCsv: (Set<Long>) -> Unit,
    onExportJson: (Set<Long>) -> Unit,
    onExportMoveScript: (Set<Long>) -> Unit,
    onExportCopyScript: (Set<Long>) -> Unit,
    onExportAll: (Set<Long>) -> Unit,
    onProceedToTransfer: () -> Unit
) {
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) onSaveExport(uri)
    }
    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }
    var filterNeedsReviewOnly by remember { mutableStateOf(false) }
    var reassignTargetItem by remember { mutableStateOf<MediaItemEntity?>(null) }

    // Track selected file IDs for Termux & Manifest export (defaults to all non-excluded items)
    var selectedExportIds by remember(mediaItems.map { it.id }) {
        mutableStateOf(
            mediaItems.filter { !it.isExcludedFromTransfer }.map { it.id }.toSet()
        )
    }

    val reviewCount = mediaItems.count { it.needsManualReview }
    val filteredItems = remember(mediaItems, selectedCategoryFilter, filterNeedsReviewOnly) {
        mediaItems.filter { item ->
            val matchesCat = selectedCategoryFilter == null || item.effectiveCategoryId == selectedCategoryFilter
            val matchesReview = !filterNeedsReviewOnly || item.needsManualReview
            matchesCat && matchesReview
        }
    }

    val activeExportItems = remember(mediaItems, selectedExportIds) {
        mediaItems.filter { selectedExportIds.contains(it.id) && !it.isExcludedFromTransfer }
    }
    val safWarningCount = remember(activeExportItems) {
        activeExportItems.count { it.filePath.isNullOrBlank() && it.uriString.startsWith("content://") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPureBlack)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.results_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(R.string.results_summary_fmt, mediaItems.size, reviewCount),
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Category filter bar with counts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedCategoryFilter == null && !filterNeedsReviewOnly,
                onClick = {
                    selectedCategoryFilter = null
                    filterNeedsReviewOnly = false
                },
                label = { Text(stringResource(R.string.filter_all_categories, mediaItems.size)) }
            )
            FilterChip(
                selected = filterNeedsReviewOnly,
                onClick = {
                    filterNeedsReviewOnly = !filterNeedsReviewOnly
                    selectedCategoryFilter = null
                },
                label = { Text(stringResource(R.string.filter_needs_review, reviewCount)) }
            )
            categories.forEach { cat ->
                val count = mediaItems.count { it.effectiveCategoryId == cat.id }
                if (count > 0 || cat.isEnabled) {
                    FilterChip(
                        selected = selectedCategoryFilter == cat.id,
                        onClick = {
                            selectedCategoryFilter = if (selectedCategoryFilter == cat.id) null else cat.id
                            filterNeedsReviewOnly = false
                        },
                        label = { Text("${categoryDisplayName(cat.id, cat.name)} ($count)") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Thumbnail Grid with per-item export selection checkbox
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredItems, key = { it.id }) { item ->
                val isSelectedForExport = selectedExportIds.contains(item.id) && !item.isExcludedFromTransfer
                MediaResultGridCard(
                    item = item,
                    isSelectedForExport = isSelectedForExport,
                    onToggleExportSelection = { checked ->
                        selectedExportIds = if (checked) {
                            selectedExportIds + item.id
                        } else {
                            selectedExportIds - item.id
                        }
                    },
                    onClick = { onInspectItem(item.id) },
                    onQuickReassign = { reassignTargetItem = item }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Export Operations Section (Termux-Compatible File Operation Export)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AmoledCardSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.export_ops_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = PrimaryPurpleLight
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = {
                                val visibleIds = filteredItems.filter { !it.isExcludedFromTransfer }.map { it.id }
                                selectedExportIds = selectedExportIds + visibleIds
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(stringResource(R.string.export_ops_select_all), style = MaterialTheme.typography.labelMedium, color = PrimaryPurpleLight)
                        }
                        TextButton(
                            onClick = {
                                val visibleIds = filteredItems.map { it.id }.toSet()
                                selectedExportIds = selectedExportIds - visibleIds
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(stringResource(R.string.export_ops_deselect_all), style = MaterialTheme.typography.labelMedium, color = TextSecondaryMuted)
                        }
                    }
                }

                Text(
                    text = stringResource(
                        R.string.export_ops_included_count_fmt,
                        activeExportItems.size,
                        mediaItems.size,
                        safWarningCount
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusEmerald
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onExportCsv(selectedExportIds) },
                        enabled = activeExportItems.isNotEmpty() && !isAnalysisRunning,
                        border = BorderStroke(1.dp, PrimaryPurple),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.btn_export_op_csv), style = MaterialTheme.typography.labelMedium, color = PrimaryPurpleLight)
                    }
                    OutlinedButton(
                        onClick = { onExportJson(selectedExportIds) },
                        enabled = activeExportItems.isNotEmpty() && !isAnalysisRunning,
                        border = BorderStroke(1.dp, PrimaryPurple),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.btn_export_op_json), style = MaterialTheme.typography.labelMedium, color = PrimaryPurpleLight)
                    }
                    OutlinedButton(
                        onClick = { onExportMoveScript(selectedExportIds) },
                        enabled = activeExportItems.isNotEmpty() && !isAnalysisRunning,
                        border = BorderStroke(1.dp, PrimaryPurple),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.btn_export_op_move_sh), style = MaterialTheme.typography.labelMedium, color = PrimaryPurpleLight)
                    }
                    OutlinedButton(
                        onClick = { onExportCopyScript(selectedExportIds) },
                        enabled = activeExportItems.isNotEmpty() && !isAnalysisRunning,
                        border = BorderStroke(1.dp, PrimaryPurple),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.btn_export_op_copy_sh), style = MaterialTheme.typography.labelMedium, color = PrimaryPurpleLight)
                    }
                    Button(
                        onClick = { onExportAll(selectedExportIds) },
                        enabled = activeExportItems.isNotEmpty() && !isAnalysisRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.btn_export_op_all), style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }

                if (lastExportResult != null) {
                    OutlinedButton(onClick = { saveExport.launch("AI_Media_Sorter_export.zip") }) {
                        Text(stringResource(R.string.export_save_zip))
                    }
                    Text(
                        text = stringResource(
                            R.string.export_ops_result_banner,
                            stringResource(R.string.export_ready),
                            lastExportResult.includedFilesCount
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusEmerald
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onProceedToTransfer,
            enabled = mediaItems.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurpleContainer)
        ) {
            Text(
                text = stringResource(R.string.btn_proceed_transfer),
                style = MaterialTheme.typography.titleMedium,
                color = PrimaryPurpleLight
            )
        }
    }

    if (reassignTargetItem != null) {
        val target = reassignTargetItem!!
        AlertDialog(
            onDismissRequest = { reassignTargetItem = null },
            containerColor = AmoledElevatedSurface,
            title = { Text(stringResource(R.string.btn_quick_reassign)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.filter { it.isEnabled }.forEach { cat ->
                        TextButton(
                            onClick = {
                                onQuickReassignCategory(target.id, cat)
                                reassignTargetItem = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${categoryDisplayName(cat.id, cat.name)} (/${cat.destinationSubfolder})",
                                color = if (cat.id == target.effectiveCategoryId) PrimaryPurpleLight else TextPrimaryWhite
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reassignTargetItem = null }) {
                    Text(stringResource(R.string.btn_cancel), color = TextSecondaryMuted)
                }
            }
        )
    }
}

@Composable
private fun MediaResultGridCard(
    item: MediaItemEntity,
    isSelectedForExport: Boolean,
    onToggleExportSelection: (Boolean) -> Unit,
    onClick: () -> Unit,
    onQuickReassign: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = AmoledCardSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (item.needsManualReview) StatusAmber.copy(alpha = 0.7f) else AmoledSubtleBorder
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(124.dp)
                    .background(AmoledElevatedSurface)
            ) {
                AsyncImage(
                    model = item.destinationUriString ?: item.uriString,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = AmoledPureBlack.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = if (item.isVideo) stringResource(R.string.badge_video) else stringResource(R.string.badge_photo),
                        style = MaterialTheme.typography.labelMedium,
                        color = PrimaryPurpleLight,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Checkbox(
                    checked = isSelectedForExport,
                    onCheckedChange = onToggleExportSelection,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )

                if (item.needsManualReview || item.isManuallyCorrected || item.isExcludedFromTransfer) {
                    Surface(
                        color = when {
                            item.isExcludedFromTransfer -> StatusCoral.copy(alpha = 0.85f)
                            item.isManuallyCorrected -> StatusEmerald.copy(alpha = 0.85f)
                            else -> StatusAmber.copy(alpha = 0.85f)
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = when {
                                item.isExcludedFromTransfer -> stringResource(R.string.badge_excluded)
                                item.isManuallyCorrected -> stringResource(R.string.badge_manual)
                                else -> stringResource(R.string.badge_review)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = AmoledPureBlack,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = categoryDisplayName(item.effectiveCategoryId, item.effectiveCategoryName),
                    style = MaterialTheme.typography.titleMedium,
                    color = PrimaryPurpleLight
                )
                Text(
                    text = stringResource(
                        R.string.confidence_calibrated_fmt,
                        (item.calibratedConfidence * 100).toInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = onQuickReassign,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.btn_quick_reassign),
                        style = MaterialTheme.typography.labelMedium,
                        color = PrimaryPurpleLight
                    )
                }
            }
        }
    }
}
