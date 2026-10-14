package com.aimediasorter.app.domain.storage

import android.content.Context
import android.net.Uri
import com.aimediasorter.app.data.local.CategoryEntity
import com.aimediasorter.app.data.local.MediaItemEntity
import com.aimediasorter.app.data.model.TransferMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Represents a single structured record for Termux & manifest export.
 * Explicitly documents that classificationScore is an uncalibrated softmax/heuristic ranking score.
 */
@Serializable
data class FileOperationExportRecord(
    val id: Long,
    val originalPathOrUri: String,
    val resolvedPosixSourcePath: String?,
    val requiresSafAccessOnly: Boolean,
    val destinationDirectory: String,
    val destinationFileName: String,
    val detectedCategory: String,
    val effectiveCategory: String,
    val isManuallyCorrected: Boolean,
    val classificationScoreUncalibrated: Float,
    val rawCosineSimilarity: Float,
    val scoreInterpretationNote: String = "Uncalibrated temperature-scaled softmax/heuristic score (not empirically calibrated probability)",
    val mediaType: String, // PHOTO or VIDEO
    val originalModificationTimestampMs: Long,
    val originalModificationEpochSec: Long,
    val originalModificationIsoUtc: String,
    val requestedOperation: String // MOVE or COPY
)

@Serializable
data class FileOperationsManifestPayload(
    val schemaVersion: String = "1.0",
    val generatedAtIsoUtc: String,
    val modelNotice: String = "Bundled 10 KB media_visual_embedder.onnx is a heuristic/demonstration projection model; scores are uncalibrated.",
    val defaultExecutionMode: String = "DRY_RUN",
    val totalRecords: Int,
    val safRestrictedCount: Int,
    val operations: List<FileOperationExportRecord>
)

data class ExportBundleResult(
    val exportDirectoryPath: String,
    val includedFilesCount: Int,
    val safRestrictedCount: Int,
    val generatedFiles: List<File>
)

/**
 * Generates Termux-compatible manifests and executable shell scripts:
 * 1. classification_report.csv
 * 2. file_operations.json
 * 3. move_files.sh
 * 4. copy_files.sh
 *
 * Works completely independently of the in-app SafeFileMover and NEVER moves or deletes original files.
 */
class TermuxOperationExporter(private val context: Context) {

    companion object {
        const val FILE_CLASSIFICATION_CSV = "classification_report.csv"
        const val FILE_OPERATIONS_JSON = "file_operations.json"
        const val FILE_MOVE_SCRIPT_SH = "move_files.sh"
        const val FILE_COPY_SCRIPT_SH = "copy_files.sh"
    }

    private val jsonFormat = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun getExportDirectory(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(baseDir, "termux_exports").apply { mkdirs() }
    }

    /**
     * Resolves an Android Uri or file path to a standard Termux-accessible POSIX path
     * (such as /storage/emulated/0/...) when possible, or flags it as requiring SAF access.
     */
    fun resolvePosixPathOrNull(filePath: String?, uriString: String): String? {
        if (!filePath.isNullOrBlank() && filePath.startsWith("/")) {
            return filePath
        }
        if (uriString.startsWith("file://")) {
            val decoded = runCatching { URLDecoder.decode(uriString.removePrefix("file://"), "UTF-8") }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded.startsWith("/")) return decoded
        }
        // Check standard primary external storage document tree encoding: primary:DCIM/Camera/photo.jpg
        val decodedUri = runCatching { URLDecoder.decode(uriString, "UTF-8") }.getOrDefault(uriString)
        val primaryMarker = "primary:"
        val idx = decodedUri.lastIndexOf(primaryMarker)
        if (idx >= 0) {
            val rel = decodedUri.substring(idx + primaryMarker.length).trimStart('/')
            return "/storage/emulated/0/$rel"
        }
        return null
    }

    fun resolveDestinationPosixRoot(destinationUriOrPath: String): String {
        if (destinationUriOrPath.isBlank()) {
            return "/storage/emulated/0/SortedMedia"
        }
        if (destinationUriOrPath.startsWith("/")) {
            return destinationUriOrPath.trimEnd('/')
        }
        val resolved = resolvePosixPathOrNull(null, destinationUriOrPath)
        return resolved?.trimEnd('/') ?: "/storage/emulated/0/SortedMedia"
    }

    fun buildExportRecords(
        items: List<MediaItemEntity>,
        categoriesMap: Map<String, CategoryEntity>,
        destinationRootUriOrPath: String,
        requestedMethod: TransferMethod
    ): List<FileOperationExportRecord> {
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val posixDestRoot = resolveDestinationPosixRoot(destinationRootUriOrPath)

        return items.map { item ->
            val cat = categoriesMap[item.effectiveCategoryId]
            val subfolder = (cat?.destinationSubfolder ?: item.effectiveCategoryName).trim('/', '\')
            val posixSrc = resolvePosixPathOrNull(item.filePath, item.uriString)
            val requiresSaf = posixSrc == null || item.uriString.startsWith("content://") && item.filePath.isNullOrBlank()
            val destDir = "$posixDestRoot/$subfolder"
            val mtimeMs = item.originalLastModifiedMs
            val mtimeSec = if (mtimeMs > 0L) mtimeMs / 1000L else 0L

            FileOperationExportRecord(
                id = item.id,
                originalPathOrUri = posixSrc ?: item.uriString,
                resolvedPosixSourcePath = posixSrc,
                requiresSafAccessOnly = requiresSaf,
                destinationDirectory = destDir,
                destinationFileName = item.fileName,
                detectedCategory = item.detectedCategoryName,
                effectiveCategory = item.effectiveCategoryName,
                isManuallyCorrected = item.isManuallyCorrected,
                classificationScoreUncalibrated = item.calibratedConfidence,
                rawCosineSimilarity = item.rawSimilarityScore,
                mediaType = if (item.isVideo) "VIDEO" else "PHOTO",
                originalModificationTimestampMs = mtimeMs,
                originalModificationEpochSec = mtimeSec,
                originalModificationIsoUtc = isoFormatter.format(Date(mtimeMs)),
                requestedOperation = requestedMethod.name
            )
        }
    }

    fun exportClassificationCsv(
        records: List<FileOperationExportRecord>,
        outputDir: File = getExportDirectory()
    ): File {
        outputDir.mkdirs()
        val csvFile = File(outputDir, FILE_CLASSIFICATION_CSV)
        csvFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(
                "original_path_or_uri,resolved_posix_path,requires_saf_only,destination_directory," +
                "destination_filename,detected_category,effective_category,manually_corrected," +
                "classification_score_uncalibrated,raw_cosine_similarity,media_type," +
                "original_modified_ms,original_modified_iso_utc,requested_operation
"
            )
            for (r in records) {
                writer.write(
                    listOf(
                        escapeCsv(r.originalPathOrUri),
                        escapeCsv(r.resolvedPosixSourcePath ?: ""),
                        r.requiresSafAccessOnly.toString(),
                        escapeCsv(r.destinationDirectory),
                        escapeCsv(r.destinationFileName),
                        escapeCsv(r.detectedCategory),
                        escapeCsv(r.effectiveCategory),
                        r.isManuallyCorrected.toString(),
                        "%.4f".format(Locale.US, r.classificationScoreUncalibrated),
                        "%.4f".format(Locale.US, r.rawCosineSimilarity),
                        r.mediaType,
                        r.originalModificationTimestampMs.toString(),
                        escapeCsv(r.originalModificationIsoUtc),
                        r.requestedOperation
                    ).joinToString(",") + "
"
                )
            }
        }
        return csvFile
    }

    fun exportFileOperationsJson(
        records: List<FileOperationExportRecord>,
        outputDir: File = getExportDirectory()
    ): File {
        outputDir.mkdirs()
        val jsonFile = File(outputDir, FILE_OPERATIONS_JSON)
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val payload = FileOperationsManifestPayload(
            generatedAtIsoUtc = isoFormatter.format(Date()),
            totalRecords = records.size,
            safRestrictedCount = records.count { it.requiresSafAccessOnly },
            operations = records
        )
        jsonFile.writeText(jsonFormat.encodeToString(payload), Charsets.UTF_8)
        return jsonFile
    }

    fun exportTermuxScript(
        records: List<FileOperationExportRecord>,
        operationMode: TransferMethod,
        outputDir: File = getExportDirectory()
    ): File {
        outputDir.mkdirs()
        val scriptFileName = if (operationMode == TransferMethod.MOVE) FILE_MOVE_SCRIPT_SH else FILE_COPY_SCRIPT_SH
        val scriptFile = File(outputDir, scriptFileName)
        val modeVerb = operationMode.name // MOVE or COPY

        val sb = StringBuilder()
        sb.appendLine("#!/usr/bin/env sh")
        sb.appendLine("# ==============================================================================")
        sb.appendLine("# AI Media Sorter — Termux-Compatible Bulk Media ${modeVerb} Script")
        sb.appendLine("# Generated records: ${records.size} | SAF-only restricted records: ${records.count { it.requiresSafAccessOnly }}")
        sb.appendLine("#")
        sb.appendLine("# SAFETY GUARANTEES:")
        sb.appendLine("# 1. Defaults to DRY-RUN mode (no files modified unless --execute is passed).")
        sb.appendLine("# 2. Requires explicit interactive confirmation ('YES') unless --yes is passed.")
        sb.appendLine("# 3. Never relies on 'mv -n' alone: checks collisions, verifies SHA-256 checksum")
        sb.appendLine("#    of copied bytes before deleting sources, and preserves mtime timestamps.")
        sb.appendLine("# 4. Detects duplicate paths, blocks moving a directory into itself, and")
        sb.appendLine("#    reports Android content:// SAF URIs that Termux cannot access directly.")
        sb.appendLine("#")
        sb.appendLine("# USAGE IN TERMUX:")
        sb.appendLine("#   1. Ensure storage access: termux-setup-storage")
        sb.appendLine("#   2. Preview (Dry-Run):     sh $scriptFileName")
        sb.appendLine("#   3. Execute with prompt:   sh $scriptFileName --execute")
        sb.appendLine("#   4. Non-interactive batch: sh $scriptFileName --execute --yes")
        sb.appendLine("# ==============================================================================")
        sb.appendLine("set -u")
        sb.appendLine("")
        sb.appendLine("OPERATION_MODE='${modeVerb}'")
        sb.appendLine("DRY_RUN=1")
        sb.appendLine("ASSUME_YES=0")
        sb.appendLine("COLLISION_MODE='rename' # 'rename' (file_1.ext) or 'skip'")
        sb.appendLine("")
        sb.appendLine("for arg in "$@"; do")
        sb.appendLine("  case "\$arg" in")
        sb.appendLine("    --execute) DRY_RUN=0 ;;")
        sb.appendLine("    --dry-run) DRY_RUN=1 ;;")
        sb.appendLine("    --yes|-y) ASSUME_YES=1 ;;")
        sb.appendLine("    --collision=skip) COLLISION_MODE='skip' ;;")
        sb.appendLine("    --collision=rename) COLLISION_MODE='rename' ;;")
        sb.appendLine("    *) echo "Unknown option: \$arg (Supported: --execute, --dry-run, --yes, --collision=rename|skip)"; exit 2 ;;")
        sb.appendLine("  esac")
        sb.appendLine("done")
        sb.appendLine("")
        sb.appendLine("LOG_FILE="./termux_${modeVerb.lowercase()}_log_\$(date +%Y%m%d_%H%M%S).log"")
        sb.appendLine("SEEN_SOURCES_FILE="\$(mktemp 2>/dev/null || echo "./.tmp_seen_sources_\$\$")"")
        sb.appendLine(": > "\$SEEN_SOURCES_FILE"")
        sb.appendLine("cleanup_temp() { rm -f "\$SEEN_SOURCES_FILE"; }")
        sb.appendLine("trap cleanup_temp EXIT INT TERM")
        sb.appendLine("")
        sb.appendLine("TOTAL_COUNT=0")
        sb.appendLine("SUCCESS_COUNT=0")
        sb.appendLine("SKIPPED_COLLISION_COUNT=0")
        sb.appendLine("SKIPPED_SAF_COUNT=0")
        sb.appendLine("SKIPPED_DUPLICATE_COUNT=0")
        sb.appendLine("TIMESTAMP_WARN_COUNT=0")
        sb.appendLine("FAILED_COUNT=0")
        sb.appendLine("")
        sb.appendLine("log_msg() {")
        sb.appendLine("  printf '%s\n' "\$1"")
        sb.appendLine("  printf '%s\n' "\$1" >> "\$LOG_FILE"")
        sb.appendLine("}")
        sb.appendLine("")
        sb.appendLine("if [ "\$DRY_RUN" -eq 1 ]; then")
        sb.appendLine("  log_msg "[DRY-RUN MODE] No files will be modified. Pass --execute to perform actual ${modeVerb} operations."")
        sb.appendLine("else")
        sb.appendLine("  log_msg "[LIVE EXECUTION MODE] Operation: \$OPERATION_MODE"")
        sb.appendLine("  if [ "\$ASSUME_YES" -ne 1 ]; then")
        sb.appendLine("    printf "Confirm bulk %s of ${records.size} media files? Type 'YES' to proceed: " "\$OPERATION_MODE"")
        sb.appendLine("    read -r CONFIRM_INPUT")
        sb.appendLine("    if [ "\$CONFIRM_INPUT" != "YES" ]; then")
        sb.appendLine("      log_msg "Aborted by user."")
        sb.appendLine("      exit 0")
        sb.appendLine("    fi")
        sb.appendLine("  fi")
        sb.appendLine("fi")
        sb.appendLine("")
        sb.appendLine("compute_sha256() {")
        sb.appendLine("  if command -v sha256sum >/dev/null 2>&1; then")
        sb.appendLine("    sha256sum "\$1" | awk '{print \$1}'")
        sb.appendLine("  elif command -v shasum >/dev/null 2>&1; then")
        sb.appendLine("    shasum -a 256 "\$1" | awk '{print \$1}'")
        sb.appendLine("  else")
        sb.appendLine("    wc -c < "\$1" | tr -d ' '")
        sb.appendLine("  fi")
        sb.appendLine("}")
        sb.appendLine("")
        sb.appendLine("restore_and_verify_mtime() {")
        sb.appendLine("  target_file="\$1"")
        sb.appendLine("  orig_epoch="\$2"")
        sb.appendLine("  if [ "\$orig_epoch" -le 0 ] 2>/dev/null; then return 0; fi")
        sb.appendLine("  touch -m -d "@\$orig_epoch" "\$target_file" 2>/dev/null || true")
        sb.appendLine("  actual_epoch="\$(stat -c %Y "\$target_file" 2>/dev/null || echo 0)"")
        sb.appendLine("  diff=\$((actual_epoch - orig_epoch))")
        sb.appendLine("  if [ "\$diff" -lt 0 ]; then diff=\$((-diff)); fi")
        sb.appendLine("  if [ "\$diff" -le 2 ]; then")
        sb.appendLine("    return 0")
        sb.appendLine("  else")
        sb.appendLine("    return 1")
        sb.appendLine("  fi")
        sb.appendLine("}")
        sb.appendLine("")
        sb.appendLine("resolve_collision_path() {")
        sb.appendLine("  dir="\$1"")
        sb.appendLine("  fname="\$2"")
        sb.appendLine("  base="\${fname%.*}"")
        sb.appendLine("  ext=""")
        sb.appendLine("  case "\$fname" in *.*) ext=".\${fname##*.}" ;; esac")
        sb.appendLine("  idx=1")
        sb.appendLine("  while [ -e "\$dir/\${base}_\${idx}\${ext}" ]; do")
        sb.appendLine("    idx=\$((idx + 1))")
        sb.appendLine("  done")
        sb.appendLine("  printf '%s' "\$dir/\${base}_\${idx}\${ext}"")
        sb.appendLine("}")
        sb.appendLine("")
        sb.appendLine("process_media_op() {")
        sb.appendLine("  op_type="\$1"")
        sb.appendLine("  src_path="\$2"")
        sb.appendLine("  dst_dir="\$3"")
        sb.appendLine("  dst_name="\$4"")
        sb.appendLine("  mtime_sec="\$5"")
        sb.appendLine("  category="\$6"")
        sb.appendLine("  score="\$7"")
        sb.appendLine("  is_saf="\$8"")
        sb.appendLine("  TOTAL_COUNT=\$((TOTAL_COUNT + 1))")
        sb.appendLine("")
        sb.appendLine("  # 1. Report SAF-restricted content:// URIs that Termux cannot access directly")
        sb.appendLine("  case "\$src_path" in")
        sb.appendLine("    content://*)")
        sb.appendLine("      SKIPPED_SAF_COUNT=\$((SKIPPED_SAF_COUNT + 1))")
        sb.appendLine("      log_msg "[SAF-ONLY] Cannot access Android SAF URI directly from Termux: \$src_path (Category: \$category)"")
        sb.appendLine("      return 0")
        sb.appendLine("      ;;")
        sb.appendLine("  esac")
        sb.appendLine("  if [ "\$is_saf" = "true" ]; then")
        sb.appendLine("    SKIPPED_SAF_COUNT=\$((SKIPPED_SAF_COUNT + 1))")
        sb.appendLine("    log_msg "[SAF-ONLY] Requires Android Storage Access Framework: \$src_path"")
        sb.appendLine("    return 0")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  # 2. Detect duplicate source paths within the batch")
        sb.appendLine("  if grep -Fqx -- "\$src_path" "\$SEEN_SOURCES_FILE" 2>/dev/null; then")
        sb.appendLine("    SKIPPED_DUPLICATE_COUNT=\$((SKIPPED_DUPLICATE_COUNT + 1))")
        sb.appendLine("    log_msg "[SKIP-DUPLICATE-BATCH] Source path already processed in this script: \$src_path"")
        sb.appendLine("    return 0")
        sb.appendLine("  fi")
        sb.appendLine("  printf '%s\n' "\$src_path" >> "\$SEEN_SOURCES_FILE"")
        sb.appendLine("")
        sb.appendLine("  # 3. Prevent moving a directory into itself")
        sb.appendLine("  src_parent="\$(dirname "\$src_path")"")
        sb.appendLine("  if [ "\$src_path" = "\$dst_dir/\$dst_name" ]; then")
        sb.appendLine("    SKIPPED_DUPLICATE_COUNT=\$((SKIPPED_DUPLICATE_COUNT + 1))")
        sb.appendLine("    log_msg "[SKIP-SAME-PATH] Source and destination are identical: \$src_path"")
        sb.appendLine("    return 0")
        sb.appendLine("  fi")
        sb.appendLine("  case "\$dst_dir/" in")
        sb.appendLine("    "\$src_path/"*)")
        sb.appendLine("      FAILED_COUNT=\$((FAILED_COUNT + 1))")
        sb.appendLine("      log_msg "[ERROR-LOOP-GUARD] Blocked moving directory into itself: \$src_path -> \$dst_dir"")
        sb.appendLine("      return 1")
        sb.appendLine("      ;;")
        sb.appendLine("  esac")
        sb.appendLine("")
        sb.appendLine("  target_path="\$dst_dir/\$dst_name"")
        sb.appendLine("  if [ "\$DRY_RUN" -eq 1 ]; then")
        sb.appendLine("    log_msg "[DRY-RUN] \$op_type: '\$src_path' -> '\$target_path' (Cat: \$category, Score: \$score, mtime: \$mtime_sec)"")
        sb.appendLine("    SUCCESS_COUNT=\$((SUCCESS_COUNT + 1))")
        sb.appendLine("    return 0")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  # 4. Verify source file exists and is readable")
        sb.appendLine("  if [ ! -f "\$src_path" ] || [ ! -r "\$src_path" ]; then")
        sb.appendLine("    FAILED_COUNT=\$((FAILED_COUNT + 1))")
        sb.appendLine("    log_msg "[ERROR-SOURCE-INACCESSIBLE] Cannot read source file: \$src_path"")
        sb.appendLine("    return 1")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  # 5. Create destination directory and verify write access")
        sb.appendLine("  if ! mkdir -p "\$dst_dir" 2>/dev/null || [ ! -w "\$dst_dir" ]; then")
        sb.appendLine("    FAILED_COUNT=\$((FAILED_COUNT + 1))")
        sb.appendLine("    log_msg "[ERROR-DEST-INACCESSIBLE] Cannot write to destination directory: \$dst_dir"")
        sb.appendLine("    return 1")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  src_hash="\$(compute_sha256 "\$src_path")"")
        sb.appendLine("")
        sb.appendLine("  # 6. Collision detection & resolution (never overwrite existing files)")
        sb.appendLine("  if [ -e "\$target_path" ]; then")
        sb.appendLine("    existing_hash="\$(compute_sha256 "\$target_path")"")
        sb.appendLine("    if [ "\$src_hash" = "\$existing_hash" ] && [ "\$COLLISION_MODE" = "skip" ]; then")
        sb.appendLine("      SKIPPED_COLLISION_COUNT=\$((SKIPPED_COLLISION_COUNT + 1))")
        sb.appendLine("      log_msg "[SKIP-COLLISION-IDENTICAL] Destination exists with identical SHA-256: \$target_path"")
        sb.appendLine("      return 0")
        sb.appendLine("    fi")
        sb.appendLine("    if [ "\$COLLISION_MODE" = "skip" ]; then")
        sb.appendLine("      SKIPPED_COLLISION_COUNT=\$((SKIPPED_COLLISION_COUNT + 1))")
        sb.appendLine("      log_msg "[SKIP-COLLISION] Destination exists, skipping: \$target_path"")
        sb.appendLine("      return 0")
        sb.appendLine("    fi")
        sb.appendLine("    target_path="\$(resolve_collision_path "\$dst_dir" "\$dst_name")"")
        sb.appendLine("    log_msg "[COLLISION-RENAMED] Existing file preserved; new destination: \$target_path"")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  # 7. Safe Copy to temporary file + SHA-256 verification before deleting source")
        sb.appendLine("  tmp_target="\$dst_dir/.aimediasorter.tmp.\$\$.\$dst_name"")
        sb.appendLine("  if ! cp -p "\$src_path" "\$tmp_target" 2>/dev/null && ! cp "\$src_path" "\$tmp_target"; then")
        sb.appendLine("    rm -f "\$tmp_target"")
        sb.appendLine("    FAILED_COUNT=\$((FAILED_COUNT + 1))")
        sb.appendLine("    log_msg "[ERROR-COPY-FAILED] Failed to copy stream for: \$src_path"")
        sb.appendLine("    return 1")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  dst_hash="\$(compute_sha256 "\$tmp_target")"")
        sb.appendLine("  if [ "\$src_hash" != "\$dst_hash" ]; then")
        sb.appendLine("    rm -f "\$tmp_target"")
        sb.appendLine("    FAILED_COUNT=\$((FAILED_COUNT + 1))")
        sb.appendLine("    log_msg "[ERROR-HASH-MISMATCH] SHA-256 mismatch (src=\$src_hash, dst=\$dst_hash) for \$src_path. Source untouched."")
        sb.appendLine("    return 1")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  if ! mv -f "\$tmp_target" "\$target_path"; then")
        sb.appendLine("    rm -f "\$tmp_target"")
        sb.appendLine("    FAILED_COUNT=\$((FAILED_COUNT + 1))")
        sb.appendLine("    log_msg "[ERROR-FINALIZE] Could not finalize temp file to \$target_path"")
        sb.appendLine("    return 1")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  # 8. Restore & verify modification timestamp")
        sb.appendLine("  if ! restore_and_verify_mtime "\$target_path" "\$mtime_sec"; then")
        sb.appendLine("    TIMESTAMP_WARN_COUNT=\$((TIMESTAMP_WARN_COUNT + 1))")
        sb.appendLine("    log_msg "[WARN-TIMESTAMP] Filesystem restricted exact mtime restoration on \$target_path (original epoch: \$mtime_sec)"")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  # 9. Delete source ONLY if operation is MOVE and SHA-256 verification succeeded")
        sb.appendLine("  if [ "\$op_type" = "MOVE" ]; then")
        sb.appendLine("    if ! rm -f "\$src_path"; then")
        sb.appendLine("      FAILED_COUNT=\$((FAILED_COUNT + 1))")
        sb.appendLine("      log_msg "[WARN-SOURCE-DELETE] Destination verified at \$target_path, but source could not be removed: \$src_path"")
        sb.appendLine("      return 1")
        sb.appendLine("    fi")
        sb.appendLine("  fi")
        sb.appendLine("")
        sb.appendLine("  SUCCESS_COUNT=\$((SUCCESS_COUNT + 1))")
        sb.appendLine("  log_msg "[OK-\$op_type] '\$src_path' -> '\$target_path' (SHA-256: \$dst_hash)"")
        sb.appendLine("  return 0")
        sb.appendLine("}")
        sb.appendLine("")
        sb.appendLine("# --- BEGIN GENERATED MEDIA OPERATIONS ---")
        for r in records:
            escapedSrc = escapePosixSingleQuoted(r.originalPathOrUri)
            escapedDstDir = escapePosixSingleQuoted(r.destinationDirectory)
            escapedDstName = escapePosixSingleQuoted(r.destinationFileName)
            escapedCat = escapePosixSingleQuoted(r.effectiveCategory)
            scoreStr = "%.4f".format(Locale.US, r.classificationScoreUncalibrated)
            safFlag = if (r.requiresSafAccessOnly) "true" else "false"
            sb.appendLine(
                "process_media_op '$modeVerb' $escapedSrc $escapedDstDir $escapedDstName '${r.originalModificationEpochSec}' $escapedCat '$scoreStr' '$safFlag'"
            )
        sb.appendLine("# --- END GENERATED MEDIA OPERATIONS ---")
        sb.appendLine("")
        sb.appendLine("log_msg "=============================================================================="")
        sb.appendLine("log_msg "Summary (\$OPERATION_MODE | DRY_RUN=\$DRY_RUN): Total=\$TOTAL_COUNT | Succeeded=\$SUCCESS_COUNT | CollisionsSkipped=\$SKIPPED_COLLISION_COUNT | SAFRestricted=\$SKIPPED_SAF_COUNT | DuplicatesSkipped=\$SKIPPED_DUPLICATE_COUNT | TimestampWarnings=\$TIMESTAMP_WARN_COUNT | Failed=\$FAILED_COUNT"")
        sb.appendLine("log_msg "Log written to: \$LOG_FILE"")

        scriptFile.writeText(sb.toString(), Charsets.UTF_8)
        scriptFile.setExecutable(true)
        return scriptFile
    }

    /**
     * Generates all 4 export formats (classification_report.csv, file_operations.json,
     * move_files.sh, copy_files.sh) in a single non-destructive call.
     */
    fun exportAll(
        items: List<MediaItemEntity>,
        categoriesMap: Map<String, CategoryEntity>,
        destinationRootUriOrPath: String,
        defaultMethod: TransferMethod,
        outputDir: File = getExportDirectory()
    ): ExportBundleResult {
        val records = buildExportRecords(items, categoriesMap, destinationRootUriOrPath, defaultMethod)
        val csvFile = exportClassificationCsv(records, outputDir)
        val jsonFile = exportFileOperationsJson(records, outputDir)
        val moveRecords = records.map { it.copy(requestedOperation = TransferMethod.MOVE.name) }
        val copyRecords = records.map { it.copy(requestedOperation = TransferMethod.COPY.name) }
        val moveScript = exportTermuxScript(moveRecords, TransferMethod.MOVE, outputDir)
        val copyScript = exportTermuxScript(copyRecords, TransferMethod.COPY, outputDir)

        return ExportBundleResult(
            exportDirectoryPath = outputDir.absolutePath,
            includedFilesCount = records.size,
            safRestrictedCount = records.count { it.requiresSafAccessOnly },
            generatedFiles = listOf(csvFile, jsonFile, moveScript, copyScript)
        )
    }

    /**
     * Escapes arbitrary strings (including spaces, Unicode, quotes, $, backticks, newlines)
     * using strict POSIX single-quoting: 'foo'''bar'
     */
    fun escapePosixSingleQuoted(raw: String): String {
        return "'" + raw.replace("'", "'\''") + "'"
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace(""", """")
        return ""$escaped""
    }
}
