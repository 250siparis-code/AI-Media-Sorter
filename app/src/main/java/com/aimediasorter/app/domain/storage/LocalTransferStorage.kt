package com.aimediasorter.app.domain.storage

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.attribute.BasicFileAttributes

class LocalTransferDocument(val file: File) : TransferDocument {
    private val fileKey = if (file.exists()) attributes().fileKey() else null
    override val id: String get() = file.canonicalPath
    override val name: String get() = file.name
    private fun attributes(): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    private fun checkIdentity() {
        require(!Files.isSymbolicLink(file.toPath())) { "Symbolic-link documents are not supported." }
        val actual = attributes()
        require(actual.isRegularFile && (fileKey == null || actual.fileKey() == fileKey)) {
            "Document identity changed; original preserved."
        }
    }
    override fun identityKey(): String? { checkIdentity(); return attributes().fileKey()?.toString() }
    override fun exists(): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    override fun size(): Long { checkIdentity(); return file.length() }
    override fun modifiedMs(): Long { checkIdentity(); return file.lastModified() }
    override fun input(): InputStream {
        checkIdentity()
        return Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }
    override fun output(): OutputStream {
        checkIdentity()
        return FileOutputStream(file, false)
    }
    override fun sync(output: OutputStream) { (output as FileOutputStream).fd.sync() }
    override fun setTimestamp(originalMs: Long): TimestampVerificationResult {
        checkIdentity()
        val accepted = originalMs > 0 && file.setLastModified(originalMs)
        val actual = file.lastModified()
        val verified = accepted && kotlin.math.abs(actual - originalMs) <= 2000
        return TimestampVerificationResult(verified, originalMs, actual,
            if (verified) "Filesystem modification time verified within 2000 ms."
            else "Filesystem modification time could not be restored; embedded metadata is unchanged.")
    }
    override fun delete(): Boolean {
        if (!exists()) return true
        checkIdentity()
        return file.delete()
    }
}

class LocalTransferDirectory(directory: File, allowedRoot: File) : TransferDirectory {
    private val root = allowedRoot.canonicalFile
    private val dir = directory.canonicalFile
    init {
        require(dir == root || dir.toPath().startsWith(root.toPath())) { "Destination escapes selected root." }
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create destination directory." }
    }
    override fun find(name: String): TransferDocument? {
        val file = File(dir, SafeRelativePath.fileName(name))
        return if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) LocalTransferDocument(file) else null
    }
    override fun createExclusive(name: String, mimeType: String): TransferDocument? {
        val file = File(dir, SafeRelativePath.fileName(name))
        return try {
            Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).close()
            LocalTransferDocument(file)
        } catch (_: FileAlreadyExistsException) { null }
    }
}
