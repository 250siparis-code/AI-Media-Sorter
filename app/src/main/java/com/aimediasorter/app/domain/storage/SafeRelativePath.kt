package com.aimediasorter.app.domain.storage

/** Category paths remain relative to the selected destination. */
object SafeRelativePath {
    fun segments(raw: String): List<String> {
        require(raw.isNotBlank() && !raw.startsWith("/") && !raw.startsWith("\\")) {
            "Category folder must be a non-empty relative path."
        }
        require(!raw.contains('\u0000') && !raw.contains(':')) { "Invalid category folder." }
        val parts = raw.replace('\\', '/').split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." }) {
            "Category folder cannot contain empty segments, '.' or '..'."
        }
        return parts
    }
    fun fileName(raw: String): String {
        require(raw.isNotBlank() && raw != "." && raw != ".." &&
            '/' !in raw && '\\' !in raw && '\u0000' !in raw) { "Invalid filename." }
        return raw
    }
}
