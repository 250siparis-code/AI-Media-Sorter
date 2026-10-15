package com.aimediasorter.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Fault-injecting provider for isolated instrumentation files only. */
class FixtureDocumentsProvider : DocumentsProvider() {
    private val base get() = File(requireNotNull(context).filesDir, "qa-documents")
    private var revoke = false
    private var corrupt = false
    private var timestampUnsupported = false
    override fun onCreate(): Boolean = true
    private fun file(id: String): File {
        val path = File(base, id)
        require(path.canonicalPath.startsWith(base.canonicalPath + "/"))
        return path
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            "qa.reset" -> {
                base.deleteRecursively()
                File(base, "source").mkdirs()
                File(base, "destination").mkdirs()
                File(base, "source/photo.jpg").writeBytes(ByteArray(4096) { (it % 251).toByte() })
                File(base, "source/photo.jpg").setLastModified(1700000000000L)
                revoke = false
                corrupt = false
                timestampUnsupported = false
                return Bundle()
            }
            "qa.fault" -> {
                revoke = arg == "revoke"
                corrupt = arg == "corrupt"
                timestampUnsupported = arg == "timestamp"
                return Bundle()
            }
            "qa.state" -> return Bundle().apply {
                val source = File(base, "source/photo.jpg")
                putBoolean("sourceExists", source.exists())
                if (source.exists()) putString("sourceHash",
                    MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                        .joinToString("") { "%02x".format(it) })
            }
            "qa.cleanup" -> { base.deleteRecursively(); return Bundle() }
        }
        return super.call(method, arg, extras)
    }
    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    private fun cursor(projection: Array<out String>?): MatrixCursor = MatrixCursor(
        projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED))
    private fun row(cursor: MatrixCursor, id: String) {
        val f = file(id)
        if (!f.exists()) throw FileNotFoundException(id)
        val flags = if (f.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        val values = mapOf<String, Any>(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to id,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to f.name,
            DocumentsContract.Document.COLUMN_MIME_TYPE to if (f.isDirectory)
                DocumentsContract.Document.MIME_TYPE_DIR else "image/jpeg",
            DocumentsContract.Document.COLUMN_FLAGS to flags,
            DocumentsContract.Document.COLUMN_SIZE to f.length(),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to if (timestampUnsupported) 0L else f.lastModified()
        )
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        if (revoke) throw SecurityException("Injected revoked access")
        return cursor(projection).also { row(it, documentId) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (revoke) throw SecurityException("Injected revoked access")
        return cursor(projection).also { cursor ->
            file(parentDocumentId).listFiles()?.forEach { row(cursor, parentDocumentId + "/" + it.name) }
        }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith(parentDocumentId.trimEnd('/') + "/")
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (revoke) throw SecurityException("Injected revoked access")
        val f = file(documentId)
        if (corrupt && mode == "r" && documentId.startsWith("destination/") && f.isFile) {
            f.writeBytes(byteArrayOf(0, 9, 1))
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(!displayName.contains('/') && displayName != "." && displayName != "..")
        val original = parentDocumentId + "/" + displayName
        var id = original
        var suffix = 1
        while (file(id).exists()) id = original + "-" + suffix++
        val f = file(id)
        check(if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile())
        return id
    }
    override fun deleteDocument(documentId: String) {
        if (revoke) throw SecurityException("Injected revoked access")
        check(file(documentId).delete())
    }
}
