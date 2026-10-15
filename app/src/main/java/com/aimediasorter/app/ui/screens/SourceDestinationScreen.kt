package com.aimediasorter.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aimediasorter.app.R
import com.aimediasorter.app.data.model.DefaultCategories
import com.aimediasorter.app.data.model.MediaTypeFilter
import com.aimediasorter.app.data.model.TransferMethod
import com.aimediasorter.app.data.preferences.AppSettings
import com.aimediasorter.app.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceDestinationScreen(
    settings: AppSettings,
    hasLoopError: Boolean,
    onSelectSource: (Uri) -> Unit,
    onSelectDestination: (Uri) -> Unit,
    onMediaTypeChange: (MediaTypeFilter) -> Unit,
    onSubfoldersToggle: (Boolean) -> Unit,
    onTransferMethodChange: (TransferMethod) -> Unit,
    onToggleExtension: (String) -> Unit,
    onContinue: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val metadataPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val sourceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            onSelectSource(uri)
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_MEDIA_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                metadataPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }
    }

    val destLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onSelectDestination(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledPureBlack)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = stringResource(R.string.folders_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.folders_subtitle),
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(stringResource(R.string.metadata_access_notice), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        // Source Directory Card
        FolderPickerCard(
            label = stringResource(R.string.source_folder_label),
            uriText = settings.sourceUri.ifBlank { stringResource(R.string.source_folder_empty) },
            buttonText = stringResource(R.string.btn_select_source),
            onClick = { sourceLauncher.launch(null) }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Destination Directory Card
        FolderPickerCard(
            label = stringResource(R.string.destination_folder_label),
            uriText = settings.destinationUri.ifBlank { stringResource(R.string.destination_folder_empty) },
            buttonText = stringResource(R.string.btn_select_destination),
            onClick = { destLauncher.launch(null) }
        )

        if (hasLoopError) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.folder_loop_error),
                color = StatusCoral,
                style = MaterialTheme.typography.labelMedium
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Photo / Video / All selector
        Text(
            text = stringResource(R.string.media_filter_label),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = settings.mediaTypeFilter == MediaTypeFilter.ALL,
                onClick = { onMediaTypeChange(MediaTypeFilter.ALL) },
                label = { Text(stringResource(R.string.filter_all_media)) }
            )
            FilterChip(
                selected = settings.mediaTypeFilter == MediaTypeFilter.PHOTOS_ONLY,
                onClick = { onMediaTypeChange(MediaTypeFilter.PHOTOS_ONLY) },
                label = { Text(stringResource(R.string.filter_photos_only)) }
            )
            FilterChip(
                selected = settings.mediaTypeFilter == MediaTypeFilter.VIDEOS_ONLY,
                onClick = { onMediaTypeChange(MediaTypeFilter.VIDEOS_ONLY) },
                label = { Text(stringResource(R.string.filter_videos_only)) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scan subfolders toggle
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.scan_subfolders_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.scan_subfolders_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = settings.includeSubfolders,
                    onCheckedChange = onSubfoldersToggle
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Move vs Copy selection
        Text(
            text = stringResource(R.string.transfer_operation_label),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = settings.transferMethod == TransferMethod.MOVE,
                onClick = { onTransferMethodChange(TransferMethod.MOVE) },
                label = { Text(stringResource(R.string.transfer_move)) }
            )
            FilterChip(
                selected = settings.transferMethod == TransferMethod.COPY,
                onClick = { onTransferMethodChange(TransferMethod.COPY) },
                label = { Text(stringResource(R.string.transfer_copy)) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Supported extensions filter
        Text(
            text = stringResource(R.string.supported_extensions_label),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DefaultCategories.ALL_SUPPORTED_EXTENSIONS.forEach { ext ->
                FilterChip(
                    selected = settings.enabledExtensions.contains(ext),
                    onClick = { onToggleExtension(ext) },
                    label = { Text(ext.uppercase()) }
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onContinue,
            enabled = settings.sourceUri.isNotBlank() && settings.destinationUri.isNotBlank() && !hasLoopError,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple)
        ) {
            Text(
                text = stringResource(R.string.btn_continue_categories),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun FolderPickerCard(
    label: String,
    uriText: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AmoledCardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AmoledSubtleBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = uriText, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onClick,
                border = BorderStroke(1.dp, PrimaryPurple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = PrimaryPurpleLight
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = buttonText, color = PrimaryPurpleLight)
            }
        }
    }
}
