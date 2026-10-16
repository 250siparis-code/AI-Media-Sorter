package com.aimediasorter.app.domain.diagnostics

import com.aimediasorter.app.domain.cloud.AnalysisCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable enum class DiagnosticScope { LAST_ANALYSIS, ALL_EVENTS }
@Serializable enum class DiagnosticFormat { JSON, TXT }
@Serializable enum class DiagnosticRequestStatus { SENT, NOT_SENT_LOCAL, NOT_SENT_PENDING, SKIPPED_CACHE, PARTIAL_OR_UNKNOWN, UNKNOWN }
@Serializable data class DiagnosticEnvironment(val appVersion: String,val buildNumber: Int,val androidVersion: String,val apiLevel: Int,val deviceModel: String)
@Serializable data class DiagnosticFileReport(val anonymousId: String,val ordinal: Long?,val status: DiagnosticStatus,
    val requestStatus: DiagnosticRequestStatus,val requestsSent: Long,val lastStage: DiagnosticStage,
    val errorCode: String?,val validation: DiagnosticValidation?,val httpStatus: Int?,val elapsedMs: Long,val eventIndexes: List<Int>)
@Serializable data class DiagnosticOperationIndex(val correlationId: String,val parentId: String?,val batchId: String?,val eventIndexes: List<Int>)
@Serializable data class DiagnosticReport(val schemaVersion: Int=2,val generatedAtMs: Long,val scope: DiagnosticScope,
    val environment: DiagnosticEnvironment,val batchId: String?,val batchSummary: DiagnosticEvent?,val files: List<DiagnosticFileReport>,
    val eventCount: Int,val events: List<DiagnosticEvent>,val operations: List<DiagnosticOperationIndex>,val summaries: List<DiagnosticEvent>,
    val droppedEvents: Long,val storageFailures: Long,val historyMayBeTruncated: Boolean=true,
    val requestDefinition: String="SENT means the complete POST body was written, not confirmed Google receipt or billing. A partial upload may precede a failure. Counts are local diagnostics; rotated history is unavailable.")
class PreparedDiagnosticExport internal constructor(val text: String,val bytes: Long,val eventCount: Int,val batchId: String?,val format: DiagnosticFormat,val scope: DiagnosticScope)
object DiagnosticExport {
    private fun technical(value: String,max: Int)=value.takeIf {
        it.length in 1..max && it.all { c->c.isLetterOrDigit() && c.code<128 || c in " ._()+-" } &&
            !it.contains("AQ.",true) && !it.contains("AIza",true) && !it.contains("Bearer",true) && !it.contains("Authorization",true)
    } ?: "REDACTED"
    fun prepare(snapshot: DiagnosticSnapshot,scope: DiagnosticScope,format: DiagnosticFormat,environment: DiagnosticEnvironment,
        generatedAtMs: Long=System.currentTimeMillis()): PreparedDiagnosticExport {
        val all=snapshot.events.mapNotNull(DiagnosticPrivacy::sanitize).asReversed().sortedBy { it.timestampMs }
        val summaries=snapshot.summaries.mapNotNull(DiagnosticPrivacy::sanitize).sortedBy { it.timestampMs }
        val batch=(summaries+all.filter { it.kind in setOf(DiagnosticKind.ANALYSIS_BATCH,DiagnosticKind.WORKER) && it.batchId!=null }).maxByOrNull { it.timestampMs }?.batchId
        require(scope!=DiagnosticScope.LAST_ANALYSIS || batch!=null) { "NO_ANALYSIS_DIAGNOSTICS" }
        val events=if(scope==DiagnosticScope.LAST_ANALYSIS) all.filter { it.batchId==batch } else all
        val selectedSummaries=if(scope==DiagnosticScope.LAST_ANALYSIS) summaries.filter { it.batchId==batch } else summaries
        val groups=events.indices.groupBy { events[it].correlationId }
        val files=groups.mapNotNull { (id,indexes)->
            if(events[indexes.first()].kind!=DiagnosticKind.MEDIA_ANALYSIS) return@mapNotNull null
            fun related(event: DiagnosticEvent): Boolean {
                var current=event.correlationId
                repeat(16) {
                    if(current==id) return true
                    current=groups[current]?.firstOrNull()?.let { events[it].parentId } ?: return false
                }
                return false
            }
            val relatedIndexes=events.indices.filter { related(events[it]) }
            val rows=relatedIndexes.map { events[it] }
            val final=rows.last()
            val sent=rows.mapNotNull { it.metrics[DiagnosticMetric.REQUESTS_SENT] }.maxOrNull() ?: 0
            val code=rows.lastOrNull { it.analysisCode!=null }?.analysisCode
            val request=when {
                sent>0 || rows.any { it.stage==DiagnosticStage.REQUEST_SENT }->DiagnosticRequestStatus.SENT
                rows.any { it.stage==DiagnosticStage.CACHE_HIT }->DiagnosticRequestStatus.SKIPPED_CACHE
                code in setOf("NETWORK","TIMEOUT","ATTEMPT_UNCERTAIN")->DiagnosticRequestStatus.PARTIAL_OR_UNKNOWN
                final.status==DiagnosticStatus.PENDING->DiagnosticRequestStatus.NOT_SENT_PENDING
                rows.any { it.stage==DiagnosticStage.REQUEST_NOT_SENT }->DiagnosticRequestStatus.NOT_SENT_LOCAL
                else->DiagnosticRequestStatus.UNKNOWN
            }
            val failure=rows.lastOrNull { it.validation!=null && it.status==DiagnosticStatus.FAILED }
                ?: rows.lastOrNull { it.status==DiagnosticStatus.FAILED && it.stage !in setOf(DiagnosticStage.COMPLETE,DiagnosticStage.FAILURE) }
                ?: rows.lastOrNull { it.status==DiagnosticStatus.FAILED && it.stage!=DiagnosticStage.COMPLETE }
            DiagnosticFileReport(id,rows.firstNotNullOfOrNull { it.metrics[DiagnosticMetric.ITEM_INDEX] },final.status,request,sent,
                failure?.stage ?: final.stage,code,rows.lastOrNull { it.validation!=null }?.validation,
                rows.lastOrNull { it.httpStatus!=null }?.httpStatus,rows.maxOf { it.durationMs },relatedIndexes)
        }
        val report=DiagnosticReport(generatedAtMs=generatedAtMs,scope=scope,
            environment=environment.copy(appVersion=technical(environment.appVersion,80),buildNumber=environment.buildNumber.coerceAtLeast(0),
                androidVersion=technical(environment.androidVersion,40),apiLevel=environment.apiLevel.coerceIn(1,100),deviceModel=technical(environment.deviceModel,80)),
            batchId=if(scope==DiagnosticScope.LAST_ANALYSIS) batch else null,
            batchSummary=selectedSummaries.lastOrNull(),files=files,eventCount=events.size,events=events,
            operations=groups.map { (id,indexes)->DiagnosticOperationIndex(id,events[indexes.first()].parentId,events[indexes.first()].batchId,indexes) },
            summaries=selectedSummaries,droppedEvents=snapshot.droppedEvents.coerceAtLeast(0),storageFailures=snapshot.storageFailures.coerceAtLeast(0))
        val text=if(format==DiagnosticFormat.JSON) DiagnosticPrivacy.json.encodeToString(report) else text(report)
        return PreparedDiagnosticExport(text,text.toByteArray(Charsets.UTF_8).size.toLong(),events.size,report.batchId,format,scope)
    }
    private fun text(report: DiagnosticReport)=buildString {
        appendLine("AI Media Sorter Diagnostic Report · schema "+report.schemaVersion)
        appendLine("Generated UTC epoch ms: "+report.generatedAtMs+" | scope: "+report.scope)
        with(report.environment) { appendLine("App: "+appVersion+" | build: "+buildNumber+" | Android: "+androidVersion+" (API "+apiLevel+") | Device model: "+deviceModel) }
        appendLine("Batch: "+(report.batchId ?: "ALL_RETAINED")+" | Events: "+report.eventCount)
        appendLine("Dropped: "+report.droppedEvents+" | storage failures this process: "+report.storageFailures)
        appendLine(report.requestDefinition)
        report.summaries.forEach { appendLine("SUMMARY "+it.batchId+" | "+it.timestampMs+" | "+it.model+" | "+it.status+" | "+it.metrics) }
        report.files.forEach { appendLine("FILE "+it.anonymousId+" | ordinal: "+it.ordinal+" | "+it.status+" | "+it.requestStatus+" | sent: "+it.requestsSent+" | failure stage: "+it.lastStage+" | code: "+it.errorCode+" | validation: "+it.validation+" | HTTP: "+it.httpStatus+" | elapsed ms: "+it.elapsedMs) }
        appendLine("EVENTS (chronological; correlation, parent and batch group related operations)")
        report.events.forEach { event->
            appendLine(event.timestampMs.toString()+" | "+event.correlationId+" | parent="+event.parentId+" | batch="+event.batchId+" | "+event.kind+" | "+event.stage+" | "+event.status+" | elapsed ms="+event.durationMs+" | code="+event.analysisCode+" | error="+event.error+" | validation="+event.validation+" | HTTP="+event.httpStatus+" | model="+event.model+" | transfer="+event.transferPhase+" | "+event.metrics)
        }
    }
}
