package com.aimediasorter.app

import com.aimediasorter.app.domain.storage.TermuxScriptBuilder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class TermuxScriptBuilderTest {
    @Test fun filenamesRemainDataAndDoNotExecuteShellSubstitutions() {
        assumeTrue(File("/bin/sh").canExecute())
        val literal = "photo'\$(printf INJECTION)'name.jpg"
        val manifest = "{\"name\":\"" + literal + "\"}"
        val script = TermuxScriptBuilder.build("print(EMBEDDED_MANIFEST_B64)\n", manifest, "COPY")
        assertFalse(script.contains(literal))
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText().trim() }
        assertEquals(0, process.waitFor())
        assertEquals(manifest, String(Base64.getDecoder().decode(result), Charsets.UTF_8))
    }
    @Test fun posixHelperPreservesLiteralQuotesUnicodeAndDollarSigns() {
        assumeTrue(File("/bin/sh").canExecute())
        val literal = "İstanbul 'Kahvaltı' \$(printf INJECTION) " + 96.toChar() + "printf OTHER" + 96.toChar() + ".jpg"
        val escaped = TermuxScriptBuilder.escapePosixSingleQuoted(literal)
        val process = ProcessBuilder("sh", "-c", "printf '%s' " + escaped).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor())
        assertEquals(literal, output)
    }
}
