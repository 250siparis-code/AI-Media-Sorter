package com.aimediasorter.app
import com.aimediasorter.app.domain.cloud.ApiKeyInput
import org.junit.Assert.*
import org.junit.Test
class ApiKeyInputTest {
    @Test fun acceptsAuthAndLegacyKeysWithoutPrefixAssumptions() {
        for(key in listOf("AQ."+"x".repeat(600),"AIza"+"a".repeat(35),"future."+"b".repeat(40))) {
            assertTrue(ApiKeyInput.canSave(key))
            assertEquals(key,ApiKeyInput.requireValid("  "+key+"\n"))
        }
    }
    @Test fun rejectsEmptyTruncatedWhitespaceAndHeaderInjection() {
        for(key in listOf("","AQ.short","x".repeat(4097),"x".repeat(30)+"\r\nHeader:value","x".repeat(30)+" internal")) {
            assertFalse(ApiKeyInput.canSave(key))
        }
    }
}
