package com.aimediasorter.app

import com.aimediasorter.app.domain.diagnostics.*
import com.aimediasorter.app.domain.cloud.*
import com.aimediasorter.app.data.local.CategoryEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DiagnosticExportTest {
    private val environment=DiagnosticEnvironment("1.1.2-private-diagnostics",5,"14",34,"Pixel 8")
    private fun event(batch: String,id: String=UUID.randomUUID().toString(),time: Long=10,stage: DiagnosticStage=DiagnosticStage.COMPLETE)=
        DiagnosticEvent(time,id,batchId=batch,kind=DiagnosticKind.MEDIA_ANALYSIS,stage=stage,status=DiagnosticStatus.FAILED,
            analysisCode="RESPONSE_JSON",model="models/gemini-test",httpStatus=200,durationMs=123)
    @Test fun bothFormatsOmitAllUntrustedTextAtIngestionAndExport() {
        val batch=UUID.randomUUID().toString()
        val secrets=listOf("AQ.synthetic-private-credential","AIzaSyntheticCredential","Authorization: Bearer private-token",
            "/storage/emulated/0/DCIM/private.jpg","content://media/private-file.jpg","family-secret.jpg","data:image/jpeg;base64,/9j/private-pixels", "{raw provider body}")
        for(secret in secrets) {
            val safe=DiagnosticPrivacy.sanitize(event(batch).copy(parentId=secret,model=secret,analysisCode=secret))!!
            val raw=DiagnosticPrivacy.json.encodeToString(safe).dropLast(1)+",\"api_key\":\"AQ.otherSecret\",\"filename\":\"personal.jpg\"}"
            val loaded=DiagnosticPrivacy.decode(raw)!!
            for(format in DiagnosticFormat.entries) {
                val report=DiagnosticExport.prepare(DiagnosticSnapshot(listOf(loaded)),DiagnosticScope.ALL_EVENTS,format,environment)
                assertFalse(report.text.contains(secret));assertFalse(report.text.contains("AQ.otherSecret"));assertFalse(report.text.contains("personal.jpg"))
                assertTrue(report.text.contains(safe.correlationId));assertTrue(report.text.contains("UNRECORDED"))
            }
        }
        assertNull(DiagnosticPrivacy.sanitize(event(batch).copy(correlationId="/personal/path.jpg")))
    }
    @Test fun lastBatchAndAllEventsAreDistinctChronologicalAndGrouped() {
        val first=UUID.randomUUID().toString();val last=UUID.randomUUID().toString();val file=UUID.randomUUID().toString()
        val summary=event(last,time=30,stage=DiagnosticStage.BATCH_SUMMARY).copy(kind=DiagnosticKind.ANALYSIS_BATCH,
            metrics=mapOf(DiagnosticMetric.TOTAL_FILES to 1,DiagnosticMetric.FAILED to 1,DiagnosticMetric.REQUESTS_SENT to 1))
        val events=listOf(event(last,file,20),event(first,time=5),event(last,file,10,DiagnosticStage.REQUEST_SENT).copy(status=DiagnosticStatus.SUCCEEDED))
        val snapshot=DiagnosticSnapshot(events,listOf(summary))
        for(format in DiagnosticFormat.entries) {
            val latest=DiagnosticExport.prepare(snapshot,DiagnosticScope.LAST_ANALYSIS,format,environment,40)
            val all=DiagnosticExport.prepare(snapshot,DiagnosticScope.ALL_EVENTS,format,environment,40)
            assertEquals(2,latest.eventCount);assertEquals(3,all.eventCount)
            assertFalse(latest.text.contains(first));assertTrue(all.text.contains(first));assertTrue(all.text.contains(file))
            assertEquals(latest.text.toByteArray(Charsets.UTF_8).size.toLong(),latest.bytes)
        }
        val json=DiagnosticExport.prepare(snapshot,DiagnosticScope.ALL_EVENTS,DiagnosticFormat.JSON,environment,40)
        val report=DiagnosticPrivacy.json.decodeFromString<DiagnosticReport>(json.text)
        assertEquals(listOf(5L,10L,20L),report.events.map { it.timestampMs })
        assertEquals(2,report.operations.size);assertEquals(2,report.schemaVersion)
        assertEquals(34,report.environment.apiLevel)
    }
    @Test fun reportsDistinguishSentLocalPendingAndSkippedWithExactValidationStage() {
        val batch=UUID.randomUUID().toString();val file=UUID.randomUUID().toString()
        val sent=event(batch,file,1,DiagnosticStage.REQUEST_SENT).copy(metrics=mapOf(DiagnosticMetric.REQUESTS_SENT to 1))
        val parse=event(batch,file,2,DiagnosticStage.JSON_PARSE).copy(validation=DiagnosticValidation.JSON_SYNTAX)
        val final=event(batch,file,3)
        val local=event(batch,time=4,stage=DiagnosticStage.REQUEST_NOT_SENT).copy(analysisCode="LOCAL_IMAGE_DECODE")
        val pending=event(batch,time=5,stage=DiagnosticStage.REQUEST_NOT_SENT).copy(status=DiagnosticStatus.PENDING,analysisCode="LIMIT_REACHED")
        val skipped=event(batch,time=6,stage=DiagnosticStage.CACHE_HIT).copy(status=DiagnosticStatus.SUCCEEDED,analysisCode="CACHE_HIT")
        val snapshot=DiagnosticSnapshot(listOf(skipped,pending,local,final,parse,sent))
        val report=DiagnosticPrivacy.json.decodeFromString<DiagnosticReport>(DiagnosticExport.prepare(snapshot,DiagnosticScope.ALL_EVENTS,DiagnosticFormat.JSON,environment).text)
        assertEquals(listOf(DiagnosticRequestStatus.SENT,DiagnosticRequestStatus.NOT_SENT_LOCAL,DiagnosticRequestStatus.NOT_SENT_PENDING,DiagnosticRequestStatus.SKIPPED_CACHE),report.files.map { it.requestStatus })
        assertEquals(DiagnosticStage.JSON_PARSE,report.files.first().lastStage)
        assertEquals(DiagnosticValidation.JSON_SYNTAX,report.files.first().validation)
        assertEquals(200,report.files.first().httpStatus)
    }
    @Test fun sinkFailuresDoNotInterruptOperationsAndIdsAreUnique()=runBlocking {
        val operation=DiagnosticOperation(DiagnosticSink { throw IllegalStateException("private.jpg AQ.secret") },DiagnosticKind.MEDIA_ANALYSIS)
        assertEquals(42,operation.measure(DiagnosticStage.PREPROCESS_IMAGE) { 42 })
        operation.failure(IllegalStateException("private.jpg AQ.secret"))
        val ids=(1..100).map { DiagnosticOperation(DiagnosticSink {},DiagnosticKind.GALLERY).id }
        assertEquals(100,ids.distinct().size)
    }
    @Test fun mockedGeminiJsonAndSchemaFailuresKeepTypedDetailsAndCorrelation()=runBlocking {
        val category=CategoryEntity("cat_food","Food","Food",0,"label",1,destinationSubfolder="Food")
        for((payload,code,detail) in listOf(Triple("{",AnalysisCode.RESPONSE_JSON,DiagnosticValidation.JSON_SYNTAX),Triple("{}",AnalysisCode.RESPONSE_SCHEMA,DiagnosticValidation.REQUIRED_FIELDS))) {
            val events=mutableListOf<DiagnosticEvent>();val operation=DiagnosticOperation(DiagnosticSink { events+=it },DiagnosticKind.MEDIA_ANALYSIS)
            val response="""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":${DiagnosticPrivacy.json.encodeToString(payload)}}]}}]}"""
            val client=GeminiClient(CloudTransport { _,_,_->CloudHttpResponse(200,response) })
            val trace=CloudRequestTrace(operation)
            try { client.classify("AQ.synthetic-only",CloudOptions(model="models/gemini-test",retries=0),listOf(category),listOf(CloudImage(byteArrayOf(1))),trace) { trace.reserved() };fail("Expected validation failure") }
            catch(error: CloudFailure) { assertEquals(code,error.diagnostic) }
            assertTrue(events.any { it.stage==DiagnosticStage.REQUEST_SENT })
            assertTrue(events.any { it.validation==detail && it.analysisCode==code.name })
            assertTrue(events.all { it.correlationId==operation.id || it.parentId==operation.id })
            assertTrue(events.any { it.kind==DiagnosticKind.GEMINI_REQUEST && it.correlationId!=operation.id })
            assertFalse(DiagnosticPrivacy.json.encodeToString(events).contains("AQ.synthetic-only"))
        }
    }
    @Test fun requestUnknownOutcomeIsNotClaimedAsNoUpload() {
        val batch=UUID.randomUUID().toString()
        val unknown=event(batch,stage=DiagnosticStage.REQUEST_NOT_SENT).copy(analysisCode="TIMEOUT")
        val report=DiagnosticPrivacy.json.decodeFromString<DiagnosticReport>(DiagnosticExport.prepare(DiagnosticSnapshot(listOf(unknown)),DiagnosticScope.ALL_EVENTS,DiagnosticFormat.JSON,environment).text)
        assertEquals(DiagnosticRequestStatus.PARTIAL_OR_UNKNOWN,report.files.single().requestStatus)
    }
    @Test(expected=IllegalArgumentException::class) fun noLastBatchIsNotInvented() {
        DiagnosticExport.prepare(DiagnosticSnapshot(),DiagnosticScope.LAST_ANALYSIS,DiagnosticFormat.JSON,environment)
    }
    @Test fun unsafeEnvironmentFieldsAreRedacted() {
        val report=DiagnosticExport.prepare(DiagnosticSnapshot(),DiagnosticScope.ALL_EVENTS,DiagnosticFormat.TXT,
            environment.copy(deviceModel="AQ.privateDeviceKey",androidVersion="/personal/path",appVersion="Authorization: Bearer token"))
        assertFalse(report.text.contains("privateDeviceKey"));assertFalse(report.text.contains("/personal/path"));assertFalse(report.text.contains("Bearer token"))
    }
}
