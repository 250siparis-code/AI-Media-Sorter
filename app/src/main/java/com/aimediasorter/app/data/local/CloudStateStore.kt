package com.aimediasorter.app.data.local
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.aimediasorter.app.domain.cloud.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Isolated feature DB; never changes transfer Room schema or records. */
class CloudStateStore(context: Context, databaseName: String = "gemini_private_state.db") : SQLiteOpenHelper(context,databaseName,null,1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cache (id TEXT PRIMARY KEY NOT NULL,result TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE models (revision INTEGER NOT NULL,name TEXT NOT NULL,PRIMARY KEY(revision,name))")
        db.execSQL("CREATE TABLE grants (id TEXT PRIMARY KEY NOT NULL,signature TEXT NOT NULL,revision INTEGER NOT NULL,expires INTEGER NOT NULL,used INTEGER NOT NULL,spent INTEGER NOT NULL,maxRequests INTEGER NOT NULL,dailyLimit INTEGER NOT NULL,perAttempt INTEGER NOT NULL,maxCost INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE daily (day TEXT PRIMARY KEY NOT NULL,used INTEGER NOT NULL,inputTokens INTEGER NOT NULL,outputTokens INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE attempts (id TEXT PRIMARY KEY NOT NULL,error TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase,old: Int,new: Int) { error("Explicit cloud-state migration required.") }
    @Synchronized fun replaceModels(revision: Int,names: List<String>) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.delete("models",null,null)
            for(n in names) db.insertOrThrow("models",null,ContentValues().apply { put("revision",revision);put("name",n) })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun models(revision: Int): List<String> = readableDatabase.rawQuery("SELECT name FROM models WHERE revision=? ORDER BY name",arrayOf(revision.toString())).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0)) }
    }
    fun authorize(signature: String,revision: Int,options: CloudOptions): String {
        options.validate()
        if(!options.ready() || options.model !in models(revision)) throw CloudFailure(CloudErrorKind.CONFIG)
        val id=UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("grants",null,ContentValues().apply {
            put("id",id);put("signature",signature);put("revision",revision)
            put("expires",System.currentTimeMillis()+3_600_000);put("used",0);put("spent",0)
            put("maxRequests",options.maxRequests);put("dailyLimit",options.maxDailyRequests)
            put("perAttempt",options.estimatedMicrosPerAttempt);put("maxCost",options.maxEstimatedBatchMicros)
        })
        return id
    }
    fun revokeAll() { writableDatabase.delete("grants",null,null) }
    private fun day()=LocalDate.now(ZoneOffset.UTC).toString()
    @Synchronized fun reserve(batch: String,signature: String,revision: Int) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val today=day()
            db.execSQL("INSERT OR IGNORE INTO daily VALUES (?,0,0,0)",arrayOf(today))
            db.rawQuery("SELECT signature,revision,expires,used,spent,maxRequests,dailyLimit,perAttempt,maxCost FROM grants WHERE id=?",arrayOf(batch)).use { c ->
                if(!c.moveToFirst() || c.getString(0)!=signature || c.getInt(1)!=revision || c.getLong(2)<System.currentTimeMillis())
                    throw CloudFailure(CloudErrorKind.CONSENT)
                val usedToday=db.rawQuery("SELECT used FROM daily WHERE day=?",arrayOf(today)).use { d -> d.moveToFirst();d.getInt(0) }
                if(c.getInt(3)>=c.getInt(5) || usedToday>=c.getInt(6) || c.getLong(4)+c.getLong(7)>c.getLong(8))
                    throw CloudFailure(CloudErrorKind.BUDGET)
                db.execSQL("UPDATE grants SET used=used+1,spent=spent+perAttempt WHERE id=?",arrayOf(batch))
                db.execSQL("UPDATE daily SET used=used+1 WHERE day=?",arrayOf(today))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun cached(id: String): CloudDecision? = readableDatabase.rawQuery("SELECT result FROM cache WHERE id=? AND updated>?",arrayOf(id,(System.currentTimeMillis()-604_800_000).toString())).use { c ->
        if(!c.moveToFirst()) null else runCatching { Json.decodeFromString<CloudDecision>(c.getString(0)) }.getOrNull()
    }
    fun begin(batch: String,cacheKey: String) {
        val id="$batch:$cacheKey"
        val result=writableDatabase.insertWithOnConflict("attempts",null,ContentValues().apply { put("id",id);put("error","NETWORK") },SQLiteDatabase.CONFLICT_IGNORE)
        if(result==-1L) {
            val kind=readableDatabase.rawQuery("SELECT error FROM attempts WHERE id=?",arrayOf(id)).use { c -> c.moveToFirst();c.getString(0) }
            throw CloudFailure(runCatching { CloudErrorKind.valueOf(kind) }.getOrDefault(CloudErrorKind.NETWORK))
        }
    }
    fun failed(batch: String,id: String,kind: CloudErrorKind) {
        writableDatabase.execSQL("UPDATE attempts SET error=? WHERE id=?",arrayOf(kind.name,"$batch:$id"))
    }
    @Synchronized fun complete(batch: String,id: String,response: CloudResponse) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.insertWithOnConflict("cache",null,ContentValues().apply {
                put("id",id);put("result",Json.encodeToString(response.decision));put("updated",System.currentTimeMillis())
            },SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("UPDATE daily SET inputTokens=inputTokens+?,outputTokens=outputTokens+? WHERE day=?",arrayOf(response.inputTokens,response.outputTokens,day()))
            db.execSQL("DELETE FROM attempts WHERE id=?",arrayOf("$batch:$id"))
            db.execSQL("DELETE FROM cache WHERE id NOT IN (SELECT id FROM cache ORDER BY updated DESC LIMIT 10000)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun recordUsage(response: CloudResponse) {
        writableDatabase.execSQL("UPDATE daily SET inputTokens=inputTokens+?,outputTokens=outputTokens+? WHERE day=?",arrayOf(response.inputTokens,response.outputTokens,day()))
    }
    fun usage(): String = readableDatabase.rawQuery("SELECT used,inputTokens,outputTokens FROM daily WHERE day=?",arrayOf(day())).use { c ->
        if(!c.moveToFirst()) "0 / 0 / 0" else c.getInt(0).toString()+" / "+c.getLong(1)+" / "+c.getLong(2)
    }
}
