package com.aimediasorter.app

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.diagnostics.*
import com.aimediasorter.app.domain.diagnostics.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiagnosticLogNativeTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun name()="qa-diagnostics-"+UUID.randomUUID()+".db"
    private fun event(batch: String,time: Long=System.currentTimeMillis())=DiagnosticEvent(time,UUID.randomUUID().toString(),
        batchId=batch,kind=DiagnosticKind.MEDIA_ANALYSIS,stage=DiagnosticStage.REQUEST_NOT_SENT,status=DiagnosticStatus.FAILED,
        analysisCode="LOCAL_IMAGE_DECODE",model="models/gemini-test")
    @Test fun rotationPersistenceAndClearLeaveOtherApplicationDataAlone()=runBlocking {
        val name=name();val sentinel=File(context.cacheDir,"diagnostic-sentinel-"+UUID.randomUUID()).apply { writeText("unchanged") }
        val batch=UUID.randomUUID().toString()
        var log=DiagnosticLog(context,name,maxEvents=8)
        try {
            repeat(20) { log.record(event(batch)) }
            assertEquals(8,log.snapshot().events.size)
            log.close();log=DiagnosticLog(context,name,maxEvents=8)
            assertEquals(8,log.snapshot().events.size)
            log.clear();assertTrue(log.snapshot().events.isEmpty());assertTrue(log.snapshot().summaries.isEmpty())
            assertEquals("unchanged",sentinel.readText())
        } finally { log.close();context.deleteDatabase(name);sentinel.delete() }
    }
    @Test fun expiredEventsAndSummariesAreRemovedAndDatabaseStaysWithinByteCap()=runBlocking {
        val name=name();val cap=262_144L;val log=DiagnosticLog(context,name,maxBytes=cap)
        try {
            val batch=UUID.randomUUID().toString()
            log.record(event(batch,time=1).copy(stage=DiagnosticStage.BATCH_SUMMARY,kind=DiagnosticKind.ANALYSIS_BATCH))
            repeat(100) { log.record(event(batch)) }
            val snapshot=log.snapshot()
            assertFalse(snapshot.events.any { it.timestampMs==1L });assertTrue(snapshot.summaries.isEmpty())
            assertTrue(snapshot.events.size<100);assertTrue(context.getDatabasePath(name).length()<=cap)
        } finally { log.close();context.deleteDatabase(name) }
    }
    @Test fun corruptedDiagnosticDatabaseDoesNotMakeRecordThrowOrBlockAnalysisCaller()=runBlocking {
        val name=name();val file=context.getDatabasePath(name).apply { parentFile!!.mkdirs();writeText("not SQLite") }
        val log=DiagnosticLog(context,name)
        try {
            log.record(event(UUID.randomUUID().toString()))
            // The bounded queue accepts without opening the corrupt store on this thread.
            val recovered=try { withTimeout(10000) { log.snapshot() } } catch(_: IllegalStateException) { null }
            assertTrue(log.recent.value.storageFailures>0 || recovered?.events?.any { it.analysisCode=="LOCAL_IMAGE_DECODE" }==true)
        } finally { log.close();context.deleteDatabase(name);file.delete() }
    }
    @Test fun crashMarkerContainsOnlyTypedMetadataAndIsRecoverable() {
        val file=AtomicFile(File(context.cacheDir,"crash-marker-"+UUID.randomUUID()))
        try {
            val error=SecurityException("AQ.private-credential /storage/private.jpg raw-response")
            DiagnosticCrash.write(file,DiagnosticCrash.type(error))
            val raw=file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
            assertFalse(raw.contains("AQ."));assertFalse(raw.contains("private.jpg"));assertFalse(raw.contains("raw-response"))
            val event=DiagnosticCrash.read(file)!!
            assertEquals(DiagnosticStage.CRASH,event.stage);assertEquals(2L,event.metrics[DiagnosticMetric.EXCEPTION_TYPE])
            assertNotNull(DiagnosticPrivacy.id(event.correlationId))
        } finally { file.delete() }
    }
    @Test fun safExportsBothScopesAndFormatsWithExactPreviewBytesAndNoSourceModification()=runBlocking {
        val authority="com.aimediasorter.app.qa.documents"
        val controlUri=Uri.parse("content://"+authority+"/control")
        val control=instrumentation.context
        val token=UUID.randomUUID().toString()
        control.startActivity(Intent().setClassName(control.packageName,FixtureGrantActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("qa.token",token))
        withTimeout(10000) { while(runCatching { context.contentResolver.call(controlUri,"qa.state",null,null)?.getString("token")==token }.getOrDefault(false).not()) delay(50) }
        val original=control.contentResolver.call(controlUri,"qa.state",null,null)!!.getString("sourceHash")
        val name=name();val log=DiagnosticLog(context,name)
        try {
            val old=UUID.randomUUID().toString();val current=UUID.randomUUID().toString()
            log.record(event(old))
            log.record(event(current).copy(analysisCode="AQ.fake-credential",parentId="/storage/private.jpg",model="content://private"))
            log.record(event(current).copy(kind=DiagnosticKind.ANALYSIS_BATCH,stage=DiagnosticStage.BATCH_SUMMARY,
                metrics=mapOf(DiagnosticMetric.TOTAL_FILES to 1,DiagnosticMetric.FAILED to 1)))
            val snapshot=log.snapshot()
            val tree=DocumentsContract.buildTreeDocumentUri(authority,"destination")
            val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"destination")
            for(scope in DiagnosticScope.entries) for(format in DiagnosticFormat.entries) {
                val prepared=DiagnosticExport.prepare(snapshot,scope,format,DiagnosticEnvironment("test",5,"14",34,"Test emulator"))
                val destination=DocumentsContract.createDocument(context.contentResolver,parent,
                    if(format==DiagnosticFormat.JSON) "application/json" else "text/plain",UUID.randomUUID().toString()+if(format==DiagnosticFormat.JSON) ".json" else ".txt")!!
                log.export(context,destination,prepared)
                val actual=context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
                assertEquals(prepared.bytes,actual.size.toLong());assertEquals(prepared.text,actual.toString(Charsets.UTF_8))
                assertFalse(prepared.text.contains("AQ.fake-credential"));assertFalse(prepared.text.contains("private.jpg"))
                assertTrue(prepared.text.contains("UNRECORDED"))
                assertEquals(scope==DiagnosticScope.ALL_EVENTS,prepared.text.contains(old))
            }
            assertEquals(original,control.contentResolver.call(controlUri,"qa.state",null,null)!!.getString("sourceHash"))
        } finally { log.close();context.deleteDatabase(name);context.contentResolver.call(controlUri,"qa.cleanup",null,null) }
    }
}
