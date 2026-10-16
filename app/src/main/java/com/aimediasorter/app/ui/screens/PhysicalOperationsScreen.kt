package com.aimediasorter.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aimediasorter.app.R
import com.aimediasorter.app.data.local.*
import com.aimediasorter.app.data.model.TransferMethod
import com.aimediasorter.app.data.repository.LiveTransferProgress
import com.aimediasorter.app.data.repository.OperationPreview
import com.aimediasorter.app.ui.theme.*

@Composable fun OperationIntentDialog(ids: Set<Long>,media: List<MediaItemEntity>,categories: List<CategoryEntity>,
    links: List<MediaCategoryLink>,onPreview: (Set<Long>,TransferMethod,Map<Long,String>)->Unit,onDismiss: ()->Unit) {
    var method by remember { mutableStateOf(TransferMethod.COPY) }
    var moveTargets by remember { mutableStateOf(mapOf<Long,String>()) }
    val selected=media.filter { it.id in ids }
    val assignments=links.filter { it.origin=="EFFECTIVE" && it.mediaId in ids }.groupBy { it.mediaId }
        .mapValues { (_,v)->v.map { it.categoryId }.toSet() }
    val valid=selected.size==ids.size && selected.all { !it.needsManualReview&&!it.isExcludedFromTransfer&&
        it.analysisStatus in setOf("PROPOSED","APPROVED") && assignments[it.id].orEmpty().isNotEmpty() }
    val chosenMove=selected.associate { it.id to (moveTargets[it.id] ?: assignments[it.id]?.singleOrNull().orEmpty()) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.virtual_plan))},
        text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.physical_separate_notice))
            TransferMethod.entries.forEach { option ->
                Row { RadioButton(method==option,{method=option});Text(stringResource(if(option==TransferMethod.COPY) R.string.physical_copy else R.string.physical_move),Modifier.padding(top=12.dp)) }
            }
            if(!valid) Text(stringResource(R.string.physical_resolve_first),color=StatusAmber)
            selected.forEach { item ->
                Text(item.fileName,style=MaterialTheme.typography.titleSmall)
                assignments[item.id].orEmpty().forEach { id ->
                    val cat=categories.firstOrNull { it.id==id }
                    Row {
                        if(method==TransferMethod.MOVE) RadioButton(chosenMove[item.id]==id,{moveTargets=moveTargets+(item.id to id)})
                        Text(cat?.let { categoryDisplayName(it.id,it.name) } ?: id,Modifier.padding(top=if(method==TransferMethod.MOVE) 12.dp else 0.dp))
                    }
                }
            }
            if(method==TransferMethod.MOVE) Text(stringResource(R.string.physical_move_multi))
        }},
        confirmButton={TextButton(enabled=valid&&(method==TransferMethod.COPY||chosenMove.values.none { it.isBlank() }),
            onClick={onPreview(ids,method,chosenMove);onDismiss()}) { Text(stringResource(R.string.physical_preview)) }},
        dismissButton={TextButton(onClick=onDismiss) { Text(stringResource(R.string.btn_cancel)) }})
}

@Composable fun OperationPreviewDialog(preview: OperationPreview,onConfirm: ()->Unit,onDismiss: ()->Unit) {
    var accepted by remember(preview) { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.physical_preview))},
        text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.physical_preview_notice))
            preview.steps.forEach { step ->
                Text(preview.names[step.mediaId].orEmpty()+" → "+step.destinationRoot+"/"+step.destinationFolder+
                    " ["+step.method+"]",style=MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.physical_timestamp_warning),color=StatusAmber)
            Row { Checkbox(accepted,{accepted=it});Text(stringResource(R.string.physical_confirm_checkbox)) }
        }},
        confirmButton={TextButton(enabled=accepted,onClick=onConfirm) { Text(stringResource(R.string.physical_execute)) }},
        dismissButton={TextButton(onClick=onDismiss) { Text(stringResource(R.string.btn_cancel)) }})
}

@Composable fun PhysicalOperationsScreen(plans: List<PhysicalPlanEntity>,media: List<MediaItemEntity>,
    progress: LiveTransferProgress,onCancel: ()->Unit,onGallery: ()->Unit,onDestination: ()->Unit) {
    Column(Modifier.fillMaxSize().background(AmoledPureBlack).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nav_operations),style=MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.physical_separate_notice))
        OutlinedButton(onClick=onDestination) { Text(stringResource(R.string.btn_select_destination)) }
        OutlinedButton(onClick=onGallery) { Text(stringResource(R.string.virtual_folders)) }
        if(progress.isRunning) {
            LinearProgressIndicator(progress={if(progress.totalToTransfer==0) 0f else progress.processedCount.toFloat()/progress.totalToTransfer},modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick=onCancel) { Text(stringResource(R.string.btn_cancel)) }
        }
        Text(progress.statusMessage,style=MaterialTheme.typography.bodySmall)
        if(plans.isEmpty()) Text(stringResource(R.string.physical_empty))
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(plans,key={it.id}) { plan ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(media.firstOrNull { it.id==plan.mediaId }?.fileName ?: plan.mediaId.toString())
                        Text(plan.method+" → "+plan.destinationFolder)
                        Text(physicalStatusLabel(plan.status),color=if(plan.status in setOf("RECOVERY_REQUIRED","COMPLETED_TIMESTAMP_WARNING","COMPLETED_SOURCE_RETAINED","CANCEL_REQUESTED")) StatusAmber else PrimaryPurpleLight)
                        if(plan.note.isNotBlank()) Text(plan.note,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable fun physicalStatusLabel(status: String)=stringResource(when(status) {
    "APPROVED" -> R.string.physical_status_approved
    "RUNNING" -> R.string.physical_status_running
    "CANCEL_REQUESTED" -> R.string.physical_status_cancelling
    "COMPLETED_VERIFIED" -> R.string.physical_status_verified
    "COMPLETED_TIMESTAMP_WARNING" -> R.string.physical_status_timestamp
    "COMPLETED_SOURCE_RETAINED" -> R.string.physical_status_retained
    "RECOVERY_REQUIRED" -> R.string.physical_status_recovery
    "SKIPPED_COLLISION" -> R.string.physical_status_collision
    "FAILED" -> R.string.physical_status_failed
    "CANCELLED" -> R.string.physical_status_cancelled
    else -> R.string.virtual_pending
})
