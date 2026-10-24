package com.aimediasorter.app.domain.storage

import java.util.Base64

/** Filenames remain JSON data, never executable shell code. */
object TermuxScriptBuilder {
    fun build(executor: String, manifestJson: String, method: String): String {
        require(method == "MOVE" || method == "COPY")
        val encoded = Base64.getEncoder().encodeToString(manifestJson.toByteArray(Charsets.UTF_8))
        return "#!/usr/bin/env sh\nset -eu\n" +
            "command -v python3 >/dev/null 2>&1 || { printf '%s\\n' 'Python 3 is required; no files changed.' >&2; exit 1; }\n" +
            "exec python3 - \"\$@\" <<'AI_MEDIA_SORTER_EXECUTOR'\n" +
            "EMBEDDED_MANIFEST_B64 = \"" + encoded + "\"\n" +
            "EMBEDDED_METHOD = \"" + method + "\"\n" +
            executor + "\nAI_MEDIA_SORTER_EXECUTOR\n"
    }
    fun escapePosixSingleQuoted(raw: String): String =
        "'" + raw.replace("'", "'\\''") + "'"
}
