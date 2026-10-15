package com.aimediasorter.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aimediasorter.app.R
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.model.OperationMode
import com.aimediasorter.app.data.model.TransferItemStatus
import com.aimediasorter.app.data.model.TransferMethod
import com.aimediasorter.app.data.repository.LiveTransferProgress
import com.aimediasorter.app.ui.theme.*

@Composable
fun FileTransferScreen(
    mediaItems: List<MediaItemEntity>,
    operationMode: OperationMode,
    transferMethod: TransferMethod,
    transferProgress: LiveTransferProgress,
    onCancelTransfer: () -> Unit,
    onConfirmAndExecuteTransfer: (Boolean) -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    val totalCount = mediaItems.size
    val excludedCount = mediaItems.count { it.isExcludedFromTransfer }
    val reviewCount = mediaItems.count { !it.isExcludedFromTransfer && it.needsManualReview && !it.isManuallyCorrected }
    val readyCount = mediaItems.count {
        !it.isExcludedFromTransfer &&
        (!it.needsManualReview || it.isManuallyCorrected) &&
        it.transferStatus in setOf(TransferItemStatus.PENDING.name, TransferItemStatus.FAILED.name, TransferItemStatus.CANCELLED.name) &&
        it.sha256Checksum != null
    }

    val groupedByFolder = remember(mediaItems) {
        mediaItems
            .filter { !it.isExcludedFromTransfer }
            .groupBy { it.effectiveCategoryName }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPureBlack)
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.transfer_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.transfer_safety_checklist),
            style = MaterialTheme.typography.bodyMedium,
            color = StatusEmerald
        )

        if (operationMode == OperationMode.ANALYSIS_ONLY) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = PrimaryPurpleContainer,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, PrimaryPurple)
            ) {
                Text(
                    text = stringResource(R.string.transfer_analysis_mode_banner),
                    style = MaterialTheme.typography.labelMedium,
                    color = PrimaryPurpleLight,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Summary counters grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(Modifier.weight(1f), stringResource(R.string.transfer_stat_total), totalCount.toString(), TextPrimaryWhite)
            StatCard(Modifier.weight(1f), stringResource(R.string.transfer_stat_ready), readyCount.toString(), StatusEmerald)
            StatCard(Modifier.weight(1f), stringResource(R.string.transfer_stat_review), reviewCount.toString(), StatusAmber)
            StatCard(Modifier.weight(1f), stringResource(R.string.transfer_stat_excluded), excludedCount.toString(), StatusCoral)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.transfer_proposed_structure),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(groupedByFolder.entries.toList(), key = { it.key }) { (folderName, itemsInCat) ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = AmoledCardSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, AmoledSubtleBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "/$folderName (${itemsInCat.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryPurpleLight
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        itemsInCat.take(4).forEach { fileItem ->
                            Text(
                                text = "• ${fileItem.fileName} [${fileItem.transferStatus}]",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        if (transferProgress.totalToTransfer > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AmoledCardSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, PrimaryPurple)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    LinearProgressIndicator(
                        progress = {
                            (transferProgress.processedCount.toFloat() / transferProgress.totalToTransfer.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = PrimaryPurple
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.transfer_result_summary,
                            transferProgress.succeededCount,
                            transferProgress.timestampVerifiedCount,
                            transferProgress.collisionResolvedCount,
                            transferProgress.failedCount
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        if (transferProgress.isRunning) {
            OutlinedButton(onClick = onCancelTransfer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
        if (transferProgress.statusMessage.isNotBlank()) {
            Text(stringResource(R.string.transfer_status_notice), style = MaterialTheme.typography.labelMedium)
        }
        if (transferProgress.processedCount > 0) {
            Text(stringResource(R.string.transfer_retained_fmt, transferProgress.sourceRetainedCount, transferProgress.skippedCount),
                style = MaterialTheme.typography.labelMedium, color = StatusAmber)
        }
        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = { showConfirmDialog = true },
            enabled = operationMode == OperationMode.MOVE_FILES && readyCount > 0 && !transferProgress.isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
        ) {
            Text(
                text = stringResource(R.string.btn_start_transfer),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            containerColor = AmoledElevatedSurface,
            title = { Text(stringResource(R.string.dialog_confirm_transfer_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dialog_confirm_transfer_msg,
                        transferMethod.name,
                        readyCount
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onConfirmAndExecuteTransfer(false)
                    }
                ) {
                    Text(stringResource(R.string.btn_start_transfer), color = PrimaryPurpleLight)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.btn_cancel), color = TextSecondaryMuted)
                }
            }
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    label: String,
    value: String,
    valueColor: Color
) {
    Surface(
        modifier = modifier,
        color = AmoledCardSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AmoledSubtleBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = value, style = MaterialTheme.typography.headlineMedium, color = valueColor)
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = TextSecondaryMuted)
        }
    }
}
