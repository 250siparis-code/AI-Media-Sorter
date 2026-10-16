package com.aimediasorter.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aimediasorter.app.R
import com.aimediasorter.app.domain.cloud.AnalysisCode
import com.aimediasorter.app.domain.diagnostics.*
import com.aimediasorter.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

/** Read-only view. Export is always initiated by a button and SAF's system document picker. */
@Composable fun DiagnosticsScreen(snapshot: DiagnosticSnapshot,exportState: String,preview: PreparedDiagnosticExport?,
    onPrepare: (DiagnosticScope,DiagnosticFormat)->Unit,onExport: (Uri)->Unit,onDismissPreview: ()->Unit,onClear: ()->Unit,onBack: ()->Unit) {
    var errorsOnly by remember { mutableStateOf(false) }
    var batchOnly by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(DiagnosticFormat.JSON) }
    var clearConfirmation by remember { mutableStateOf(false) }
    val latest=snapshot.summaries.firstOrNull()
    val currentBatch=snapshot.events.firstOrNull { it.kind==DiagnosticKind.ANALYSIS_BATCH }?.batchId ?: latest?.batchId
    val summary=snapshot.summaries.firstOrNull { it.batchId==currentBatch }
    val jsonLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) onExport(uri) }
    val textLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if(uri!=null) onExport(uri) }
    if(preview!=null) AlertDialog(onDismissRequest=onDismissPreview,title={Text(stringResource(R.string.diagnostics_preview))},
        text={Text(stringResource(R.string.diagnostics_preview_size,preview.bytes,preview.eventCount))},
        confirmButton={TextButton(modifier=Modifier.testTag("diagnostics-confirm-export"),onClick={
            if(preview.format==DiagnosticFormat.JSON) jsonLauncher.launch("AI_Media_Sorter_diagnostics.json") else textLauncher.launch("AI_Media_Sorter_diagnostics.txt")
        }) { Text(stringResource(R.string.diagnostics_save)) }},
        dismissButton={TextButton(onClick=onDismissPreview) { Text(stringResource(R.string.btn_cancel)) }})
    if(clearConfirmation) AlertDialog(onDismissRequest={clearConfirmation=false},title={Text(stringResource(R.string.diagnostics_clear))},
        text={Text(stringResource(R.string.diagnostics_clear_confirm))},
        confirmButton={TextButton(onClick={clearConfirmation=false;onClear()}) { Text(stringResource(R.string.diagnostics_clear)) }},
        dismissButton={TextButton(onClick={clearConfirmation=false}) { Text(stringResource(R.string.btn_cancel)) }})
    val events=remember(snapshot,errorsOnly,batchOnly,currentBatch) { snapshot.events.filter {
        (!batchOnly || it.batchId==currentBatch) && (!errorsOnly || it.status in setOf(DiagnosticStatus.FAILED,DiagnosticStatus.PENDING,DiagnosticStatus.NEEDS_REVIEW))
    } }
    LazyColumn(Modifier.fillMaxSize().background(AmoledPureBlack).testTag("diagnostics-screen").padding(12.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick=onBack) { Text(stringResource(R.string.btn_back)) }
            Text(stringResource(R.string.diagnostics_title),style=MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.diagnostics_privacy),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.diagnostics_retention),style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.diagnostics_health,snapshot.droppedEvents,snapshot.storageFailures),
                style=MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(errorsOnly,{errorsOnly=!errorsOnly},label={Text(stringResource(R.string.diagnostics_errors))})
                FilterChip(batchOnly,{batchOnly=!batchOnly},enabled=currentBatch!=null,label={Text(stringResource(R.string.diagnostics_batch))})
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                DiagnosticFormat.entries.forEach { item-> FilterChip(format==item,{format=item},label={Text(item.name)}) }
            }
            OutlinedButton(enabled=currentBatch!=null && exportState !in setOf("SAVING","PREPARING"),onClick={
                onPrepare(DiagnosticScope.LAST_ANALYSIS,format)
            },modifier=Modifier.fillMaxWidth().testTag("diagnostics-export-batch")) { Text(stringResource(R.string.diagnostics_export_batch)) }
            OutlinedButton(enabled=exportState !in setOf("SAVING","PREPARING"),onClick={
                onPrepare(DiagnosticScope.ALL_EVENTS,format)
            },modifier=Modifier.fillMaxWidth().testTag("diagnostics-export-all")) { Text(stringResource(R.string.diagnostics_export_all)) }
            TextButton(onClick={clearConfirmation=true},modifier=Modifier.testTag("diagnostics-clear")) { Text(stringResource(R.string.diagnostics_clear)) }
            if(exportState!="IDLE") Text(stringResource(when(exportState) {
                "PREPARING","SAVING"->R.string.diagnostics_saving;"CLEARED"->R.string.diagnostics_cleared;"SAVED"->R.string.diagnostics_saved;else->R.string.diagnostics_export_failed
            }),modifier=Modifier.testTag("diagnostics-export-status"))
        }
        summary?.let { row -> item {
            Text(stringResource(R.string.diagnostics_summary),style=MaterialTheme.typography.titleMedium)
            Text(row.batchId.orEmpty(),style=MaterialTheme.typography.labelSmall)
            fun count(metric: DiagnosticMetric)=row.metrics[metric]?.coerceIn(0,Int.MAX_VALUE.toLong())?.toInt() ?: -1
            AnalysisResultSummary(count(DiagnosticMetric.SUCCESSFUL),count(DiagnosticMetric.NEEDS_REVIEW),
                count(DiagnosticMetric.FAILED),count(DiagnosticMetric.PENDING),count(DiagnosticMetric.REQUESTS_SENT))
        } }
        if(events.isEmpty()) item { Text(stringResource(R.string.diagnostics_empty)) }
        itemsIndexed(events,key={index,event->event.correlationId+":"+index}) { _,event ->
            Surface(color=AmoledCardSurface,border=BorderStroke(1.dp,AmoledSubtleBorder),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(DateFormat.getDateTimeInstance().format(Date(event.timestampMs)),style=MaterialTheme.typography.labelSmall)
                    Text(event.kind.name+" / "+event.stage.name+" / "+event.status.name,style=MaterialTheme.typography.labelMedium,
                        color=if(event.status==DiagnosticStatus.FAILED) StatusAmber else PrimaryPurpleLight)
                    Text(event.correlationId,style=MaterialTheme.typography.labelSmall)
                    event.parentId?.let { Text("parent: "+it,style=MaterialTheme.typography.labelSmall) }
                    Text(stringResource(R.string.diagnostics_elapsed,event.durationMs),style=MaterialTheme.typography.labelSmall)
                    event.analysisCode?.let { Text(it+": "+diagnosticText(AnalysisCode.safely(it)),style=MaterialTheme.typography.bodySmall) }
                    if(event.error!=DiagnosticError.NONE) Text(event.error.name)
                    event.validation?.let { Text(it.name,style=MaterialTheme.typography.bodySmall) }
                    event.httpStatus?.let { Text("HTTP "+it) }
                    event.model?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
                    event.transferPhase?.let { Text(it.name,style=MaterialTheme.typography.labelSmall) }
                    if(event.metrics.isNotEmpty()) Text(event.metrics.entries.joinToString { it.key.name+"="+it.value },style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
