package com.aimediasorter.app.domain.cloud

/** Local transport safety only; prefixes do not establish validity or authentication. */
object ApiKeyInput {
    fun normalized(value: String) = value.trim()
    fun canSave(value: String): Boolean {
        val key = normalized(value)
        return key.length in 20..4096 && key.all { it.code in 33..126 }
    }
    fun requireValid(value: String): String {
        require(canSave(value)) { "Enter a complete key without internal whitespace or control characters." }
        return normalized(value)
    }
}
