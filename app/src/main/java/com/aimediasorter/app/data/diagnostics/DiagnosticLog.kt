package com.aimediasorter.app.data.diagnostics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.AtomicFile
import com.aimediasorter.app.domain.diagnostics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** App-private, bounded, no WAL and no networking. One IO writer; callers only trySend. */
class DiagnosticLog(context: Context,databaseName: String="diagnostics.db",
    private val maxEvents: Int=4000,private val retentionMs: Long=7*24*60*60*1000L,private val maxBytes: Long=10_000_000L) : DiagnosticSink {
    private val database=Store(context.applicationContext,databaseName,maxBytes,retentionMs)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private sealed interface Command {
        data class Append(val event: DiagnosticEvent): Command
        data object Wake: Command
        data class Snapshot(val result: CompletableDeferred<DiagnosticSnapshot>): Command
        data class Clear(val result: CompletableDeferred<Unit>): Command
    }
    private val queue=Channel<Command>(512)
    private val pendingSummaries=java.util.concurrent.ConcurrentHashMap<String,DiagnosticEvent>()
    private val dropped=AtomicLong()
    private val failures=AtomicLong()
    private val _recent=MutableStateFlow(DiagnosticSnapshot())
    val recent=_recent.asStateFlow()
    private var lastPublish=0L
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writer=scope.launch {
        try { publish() } catch(_: Exception) { failures.incrementAndGet() }
        var lastDropped=0L;var lastFailures=0L
        val healthId=UUID.randomUUID().toString()
        for(command in queue) {
            try {
                // A final batch summary must survive an ordinary-event queue overflow.
                pendingSummaries.entries.toList().forEach { (batch,event) ->
                    if(pendingSummaries.remove(batch,event)) database.append(listOf(event),maxEvents,retentionMs)
                }
                when(command) {
                    Command.Wake -> Unit
                    is Command.Append -> {
                        val events=mutableListOf(command.event)
                        // Batch only append commands; snapshot is a FIFO barrier.
                        database.append(events,maxEvents,retentionMs)
                        if(dropped.get()>lastDropped || failures.get()>lastFailures) {
                            database.append(listOf(DiagnosticEvent(System.currentTimeMillis(),healthId,
                                kind=DiagnosticKind.APPLICATION,
                                stage=if(failures.get()>lastFailures) DiagnosticStage.STORAGE_FAILURE else DiagnosticStage.QUEUE_OVERFLOW,
                                status=DiagnosticStatus.INFO,error=if(failures.get()>lastFailures) DiagnosticError.LOG_STORAGE_FAILED else DiagnosticError.QUEUE_FULL,
                                metrics=mapOf(DiagnosticMetric.DROPPED_EVENTS to dropped.get(),DiagnosticMetric.STORAGE_FAILURES to failures.get()))),maxEvents,retentionMs)
                            lastDropped=dropped.get();lastFailures=failures.get()
                        }
                        if(System.nanoTime()-lastPublish>250_000_000L) publish()
                    }
                    is Command.Clear -> {
                        pendingSummaries.clear();database.clear();dropped.set(0);failures.set(0);lastDropped=0;lastFailures=0;publish();command.result.complete(Unit)
                    }
                    is Command.Snapshot -> {
                        publish()
                        command.result.complete(read(4000))
                    }
                }
            } catch(_: Exception) {
                failures.incrementAndGet()
                if(command is Command.Snapshot) command.result.completeExceptionally(IllegalStateException("DIAGNOSTIC_STORAGE"))
                if(command is Command.Clear) command.result.completeExceptionally(IllegalStateException("DIAGNOSTIC_STORAGE"))
                _recent.value=_recent.value.copy(storageFailures=failures.get(),droppedEvents=dropped.get())
            }
            // Publish the final event too, even when no further events arrive.
            if(queue.isEmpty) try { publish() } catch(_: Exception) { failures.incrementAndGet() }
        }
    }
    private fun read(limit: Int)=DiagnosticSnapshot(database.events(limit),database.summaries(),dropped.get(),failures.get())
    private fun publish() { _recent.value=read(200);lastPublish=System.nanoTime() }
    override fun record(event: DiagnosticEvent) {
        val safe=DiagnosticPrivacy.sanitize(event) ?: return
        if(safe.stage==DiagnosticStage.BATCH_SUMMARY && safe.batchId!=null) {
            pendingSummaries[safe.batchId]=safe
            if(pendingSummaries.size>20) pendingSummaries.entries.minByOrNull { it.value.timestampMs }?.let { pendingSummaries.remove(it.key,it.value) }
            queue.trySend(Command.Wake)
            return
        }
        if(!queue.trySend(Command.Append(safe)).isSuccess) dropped.incrementAndGet()
    }
    fun operation(kind: DiagnosticKind,batchId: String?=null,parentId: String?=null,model: String?=null)=
        DiagnosticOperation(this,kind,batchId,parentId,model)
    suspend fun snapshot(): DiagnosticSnapshot {
        val reply=CompletableDeferred<DiagnosticSnapshot>()
        queue.send(Command.Snapshot(reply))
        return reply.await()
    }
    suspend fun clear() {
        val reply=CompletableDeferred<Unit>()
        queue.send(Command.Clear(reply));reply.await()
    }
    /** Writes exactly the previewed bytes, only to a user-selected SAF destination. */
    suspend fun export(context: Context,uri: Uri,prepared: PreparedDiagnosticExport)=withContext(Dispatchers.IO) {
        val operation=operation(DiagnosticKind.EXPORT,prepared.batchId)
        try {
            val bytes=prepared.text.toByteArray(Charsets.UTF_8)
            context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(bytes);it.flush() }
                ?: error("DIAGNOSTIC_EXPORT")
            operation.event(DiagnosticStage.COMPLETE,DiagnosticStatus.SUCCEEDED,metrics=mapOf(DiagnosticMetric.EVENT_COUNT to prepared.eventCount.toLong()))
        } catch(cancelled: CancellationException) { operation.failure(cancelled);throw cancelled }
        catch(_: Exception) {
            operation.event(DiagnosticStage.FAILURE,DiagnosticStatus.FAILED,error=DiagnosticError.EXPORT_FAILED)
            throw IllegalStateException("DIAGNOSTIC_EXPORT")
        }
    }
    suspend fun close() { queue.close();writer.join();database.close();scope.cancel() }

    private class Store(context: Context,name: String,private val maxBytes: Long,private val retentionMs: Long): SQLiteOpenHelper(context,name,null,2) {
        override fun onConfigure(db: SQLiteDatabase) { val pageBytes=db.pageSize;db.setMaximumSize(maxBytes.coerceAtLeast(65_536L)/pageBytes*pageBytes) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events(seq INTEGER PRIMARY KEY, ts INTEGER NOT NULL, bytes INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE summaries(batch TEXT PRIMARY KEY, ts INTEGER NOT NULL, payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase,oldVersion: Int,newVersion: Int) {
            if(oldVersion<2) {
                db.execSQL("ALTER TABLE events ADD COLUMN bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE events SET bytes=length(CAST(payload AS BLOB))")
            }
        }
        fun append(events: List<DiagnosticEvent>,maxEvents: Int,retentionMs: Long) {
            val db=writableDatabase
            db.beginTransaction()
            try {
                // Prune before and after insert so a full database can make progress.
                db.execSQL("DELETE FROM events WHERE ts < ?",arrayOf(System.currentTimeMillis()-retentionMs))
                db.execSQL("DELETE FROM events WHERE seq NOT IN (SELECT seq FROM events ORDER BY seq DESC LIMIT ?)",arrayOf(maxEvents.coerceIn(1,4000)-1))
                for(event in events) {
                    val payload=DiagnosticPrivacy.json.encodeToString(event)
                    val size=payload.toByteArray(Charsets.UTF_8).size
                    require(size<=4096)
                    // Track actual UTF-8 payload bytes; reserve ample room for page fragmentation,
                    // summaries and indexes while retaining small events efficiently.
                    val budget=((maxBytes-65_536L).coerceAtLeast(16_384L)*0.4).toLong()
                    var retained=android.database.DatabaseUtils.longForQuery(db,"SELECT COALESCE(SUM(bytes),0) FROM events",null)
                    while(retained+size>budget) {
                        val removed=db.rawQuery("SELECT seq,bytes FROM events ORDER BY seq LIMIT 1",null).use { c ->
                            if(c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
                        } ?: break
                        db.delete("events","seq=?",arrayOf(removed.first.toString()));retained-=removed.second
                    }
                    db.insertOrThrow("events",null,ContentValues().apply { put("ts",event.timestampMs);put("bytes",size);put("payload",payload) })
                    if(event.stage==DiagnosticStage.BATCH_SUMMARY && event.batchId!=null)
                        db.insertWithOnConflict("summaries",null,ContentValues().apply {
                            put("batch",event.batchId);put("ts",event.timestampMs);put("payload",payload)
                        },SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.execSQL("DELETE FROM summaries WHERE ts < ?",arrayOf(System.currentTimeMillis()-retentionMs))
                db.execSQL("DELETE FROM summaries WHERE batch NOT IN (SELECT batch FROM summaries ORDER BY ts DESC LIMIT 20)")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        fun events(limit: Int)=read("SELECT payload FROM events WHERE ts >= "+(System.currentTimeMillis()-retentionMs)+" ORDER BY seq DESC LIMIT "+limit.coerceIn(1,4000))
        fun summaries()=read("SELECT payload FROM summaries WHERE ts >= "+(System.currentTimeMillis()-retentionMs)+" ORDER BY ts DESC LIMIT 20")
        fun clear() { writableDatabase.execSQL("DELETE FROM events");writableDatabase.execSQL("DELETE FROM summaries");writableDatabase.execSQL("VACUUM") }
        private fun read(sql: String): List<DiagnosticEvent> = readableDatabase.rawQuery(sql,null).use { c ->
            buildList { while(c.moveToNext()) DiagnosticPrivacy.decode(c.getString(0))?.let { add(it) } }
        }
    }
}

/** Crash marker contains a constant exception TYPE number only, never message, stack, thread name or path. */
object DiagnosticCrash {
    fun type(error: Throwable): Long=when(error) {
        is OutOfMemoryError->1;is SecurityException->2;is java.io.IOException->3
        is CancellationException->4;else->0
    }
    fun write(file: AtomicFile,type: Long) {
        var output: java.io.FileOutputStream?=null
        try {
            output=file.startWrite()
            output.write((System.currentTimeMillis().toString()+"|"+UUID.randomUUID()+"|"+type.coerceIn(0,4)).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch(_: Throwable) { if(output!=null) try { file.failWrite(output) } catch(_: Throwable) { } }
    }
    fun read(file: AtomicFile): DiagnosticEvent?=try {
        if(file.baseFile.length()>128) null else {
            val parts=file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }.split("|")
            if(parts.size!=3) null else DiagnosticPrivacy.sanitize(DiagnosticEvent(parts[0].toLong(),parts[1],
                kind=DiagnosticKind.APPLICATION,stage=DiagnosticStage.CRASH,status=DiagnosticStatus.FAILED,
                metrics=mapOf(DiagnosticMetric.EXCEPTION_TYPE to parts[2].toLong().coerceIn(0,4))))
        }
    } catch(_: Exception) { null }
}
object Diagnostics : DiagnosticSink {
    @Volatile private var log: DiagnosticLog?=null
    override fun record(event: DiagnosticEvent) { log?.record(event) }
    fun operation(kind: DiagnosticKind,batchId: String?=null,parentId: String?=null,model: String?=null)=
        DiagnosticOperation(this,kind,batchId,parentId,model)
    @Synchronized fun initialize(context: Context): DiagnosticLog {
        log?.let { return it }
        val created=DiagnosticLog(context)
        log=created
        val marker=AtomicFile(File(context.filesDir,"diagnostic-crash-marker"))
        val app=operation(DiagnosticKind.APPLICATION)
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        try { Thread.setDefaultUncaughtExceptionHandler { thread,error ->
            try { DiagnosticCrash.write(marker,DiagnosticCrash.type(error)) } finally { previous?.uncaughtException(thread,error) }
        } } catch(_: Exception) { }
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val snapshot=created.snapshot()
                snapshot.events.groupBy { it.correlationId }.values.filter { events ->
                    val latest=events.first()
                    latest.kind in setOf(DiagnosticKind.ANALYSIS_BATCH,DiagnosticKind.WORKER,DiagnosticKind.COPY,DiagnosticKind.MOVE) &&
                        events.none { it.stage in setOf(DiagnosticStage.COMPLETE,DiagnosticStage.CANCELLED,DiagnosticStage.FAILURE,DiagnosticStage.PROCESS_RECOVERY) }
                }.forEach { events ->
                    val old=events.first()
                    created.record(old.copy(timestampMs=System.currentTimeMillis(),stage=DiagnosticStage.PROCESS_RECOVERY,
                        status=DiagnosticStatus.NEEDS_REVIEW,error=DiagnosticError.PROCESS_INTERRUPTED,metrics=emptyMap()))
                }
            } catch(_: Exception) { }
            try {
                val seen=context.getSharedPreferences("diagnostic_exit_state",Context.MODE_PRIVATE)
                val lastSeen=seen.getLong("last_exit",0L)
                val exits=context.getSystemService(android.app.ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName,0,3)
                exits.filter { it.timestamp>maxOf(lastSeen,System.currentTimeMillis()-7*24*60*60*1000L) &&
                    it.reason in setOf(android.app.ApplicationExitInfo.REASON_CRASH,android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,android.app.ApplicationExitInfo.REASON_ANR) }.forEach { exit ->
                    // Never read description, process name, traceInputStream or other free text.
                    created.record(DiagnosticEvent(exit.timestamp,UUID.randomUUID().toString(),parentId=app.id,
                        kind=DiagnosticKind.APPLICATION,stage=DiagnosticStage.CRASH,status=DiagnosticStatus.FAILED,
                        metrics=mapOf(DiagnosticMetric.PROCESS_EXIT_REASON to exit.reason.toLong(),
                            DiagnosticMetric.PROCESS_EXIT_STATUS to exit.status.toLong(),DiagnosticMetric.RSS_KB to exit.rss)))
                }
                exits.maxOfOrNull { it.timestamp }?.let { seen.edit().putLong("last_exit",maxOf(it,lastSeen)).apply() }
            } catch(_: Exception) { }
            DiagnosticCrash.read(marker)?.let {
                created.record(it)
                app.event(DiagnosticStage.PROCESS_RECOVERY,DiagnosticStatus.NEEDS_REVIEW,error=DiagnosticError.PROCESS_INTERRUPTED)
            }
            marker.delete()
            app.event(DiagnosticStage.COMPLETE,DiagnosticStatus.SUCCEEDED,metrics=mapOf(
                DiagnosticMetric.API_LEVEL to android.os.Build.VERSION.SDK_INT.toLong(),
                DiagnosticMetric.PAGE_BYTES to runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrDefault(0L)))
        }
        return created
    }
}

/** A wrapper only: execution, cancellation and error propagation are preserved. */
suspend fun <T> diagnosed(kind: DiagnosticKind,batchId: String?=null,parentId: String?=null,
    model: String?=null,block: suspend (DiagnosticOperation)->T): T {
    val inherited=currentCoroutineContext()[DiagnosticTraceContext]?.operation
    val operation=Diagnostics.operation(kind,batchId ?: inherited?.batchId,parentId ?: inherited?.id,model)
    return try { withContext(DiagnosticTraceContext(operation)) { block(operation) }.also { operation.event(DiagnosticStage.COMPLETE,DiagnosticStatus.SUCCEEDED) } }
    catch(error: Throwable) { operation.failure(error);throw error }
}
