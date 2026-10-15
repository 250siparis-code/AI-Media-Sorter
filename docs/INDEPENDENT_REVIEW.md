# Independent Technical Review and Fix Record

Review baseline: main commit b32444218f4a5e262803199abbd70e98df786ffe.
Proposed fixes: codex/audit-safety-and-build-fixes. Main was not overwritten.
Source of truth: the GitHub repository. The supplied ZIP is excluded from the fix review.

## Project Status

The public repository is accessible. It contains a single native Android application module, Kotlin/Compose/Material 3 screens, a repository layer, Room persistence, DataStore settings and WorkManager workers. Existing functional architecture and screens were retained.

The build uses AGP 8.7.2, Kotlin 2.0.21, JDK 17, Gradle 8.9, compile/target SDK 35 and minimum SDK 29. The official wrapper scripts/JAR and distribution checksum are checked in. The wrapper JAR's SHA-256 was independently compared with Gradle's release checksum.

Before fixes, TermuxOperationExporter contained invalid mixed Python/Kotlin source; backup-rule validation also failed during independent CI. The repaired branch passed a clean debug build, lint, 21 JVM tests and 14 Python tests at bf01a9752def0c258b92e1b65ffc72efb77ca6a9:
https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38661853545

Native inference/storage tests and APK inspection have since been added. Their final results must be read from the final verification record below; the earlier green run did not execute Android instrumentation.

## AI System

Default runtime: bundled Google ML Kit Image Labeling, com.google.mlkit:image-labeling:17.0.9. The model's architecture/weight revision is controlled by that SDK; this review does not assign an unsupported exact backbone name. SDK output labels and their actual confidences are mapped by LabelCategoryMapper. Automatic scores contain no demonstration ONNX mixing or invented calibration boost.

The project asset models/media_visual_embedder.onnx is a small handcrafted 64-feature projection, not a trained semantic image/text encoder. It is retained only for diagnostic execution with mandatory review. Tensor input/output sizes are checked and sessions/options/tensors are closed.

Custom MobileCLIP is unavailable: importing an image encoder alone does not supply compatible trained text embeddings or a tokenizer. Saved unsupported custom-engine settings fall back to the real bundled default. Custom category descriptions are keyword matches against SDK label evidence.

Videos are decoded at several requested times with scaled native frames. Distinct frame content is SHA-256 deduplicated. Frame uncertainty, too few distinct samples and competing scene categories force review. Aggregation uses mean model score and vote share, without an artificial confidence floor.

No representative labeled evaluation set is present. People, food, vehicle, animal and nature accuracy, primary-subject behavior, custom-category coverage, unknown rejection and threshold calibration are **unestablished**. Synthetic native tests prove execution and plumbing, not accuracy.

References:
- https://developers.google.com/ml-kit/vision/image-labeling/android
- https://developers.google.com/ml-kit/terms
- https://github.com/microsoft/onnxruntime/blob/main/LICENSE

ML Kit SDK/model terms and provenance of the diagnostic weights need maintainer review before distribution. Open-source runtime licensing does not automatically license every model asset.

## File Safety

SafeFileMover delegates to VerifiedTransferEngine for both local and SAF operations. The engine exclusively stages and publishes, reads back SHA-256 and byte count, verifies source identity/content repeatedly, restores modification time separately and records checkpoints. Existing destination files are never opened with replace semantics.

MOVE requires checked source deletion, stable identities, durable destination publication and the selected timestamp policy. An unsupported provider produces a retained original and an actual COPY result. A failed deletion is not reported as a completed MOVE.

OriginalMediaAccess requests original MediaStore bytes and original-format typed streams. Android's platform FileSystemProvider can otherwise route SAF reads through compatibility transcoding. Unavailable original access blocks automatic transfer. Non-platform providers remain subject to their original-document stream contract.

Room records per-source scan sessions and transfer journals. Startup reconciliation flags interrupted destinations for review, verifying bytes where accessible, and never resumes deletion. Schema 1→2 migration is non-destructive; old unscoped rows are retained but require a new scan before transfer.

Filesystem mtime is obtained from an actual local file or regular-file descriptor where exposed. EXIF/video capture dates and provider dates are kept distinct. Restoration success requires readback. A provider without reliable mtime reports the limitation rather than claiming preservation.

Safety depends on a conforming provider and storage behaving according to its reported identity/durability contract. No application can make an arbitrary third-party provider or sudden hardware failure fully transactional. Destructive acceptance on a physical device and its actual provider remains a separate release gate.

## Privacy and Security

The reviewed application source contains no media-upload endpoint, embedded paid-API secret or advertising tracker. The inspected merged debug APK has no INTERNET or MANAGE_EXTERNAL_STORAGE permission; normal WorkManager permissions include boot handling, wake locks and network-state observation. Media access uses explicitly selected SAF grants, with metadata access requested only for original-byte preservation. Backups are disabled and exports remain in private cache until the user saves them.

Google's ML Kit terms document SDK usage/performance metrics. The dependency therefore must not be described as telemetry-free. This APK denies app-owned Internet access, and Google's published terms state media inputs and inference outputs are processed on-device. Privacy disclosures and dependency behavior still require review for a distributed release; absence of application upload code is not a source-level audit of closed-source SDK internals.

## Defects and Targeted Fixes

| Severity | Baseline defect / evidence | Changed files and implementation | Expected/current behavior |
| --- | --- | --- | --- |
| CRITICAL | Source move/delete could precede destination readback; source/destination races and replacement risk | SafeFileMover.kt, VerifiedTransferEngine.kt, LocalTransferStorage.kt | Verify staging/final bytes; exclusive creation; recheck both after deletion checkpoint; keep originals on uncertainty |
| CRITICAL | Transfers were not consistently gated by Analysis Mode and current source | MediaRepository.kt, MainViewModel.kt, Daos.kt, FileTransferScreen.kt | Authoritative mode/source/signature checks, scoped latest scan and live deletion guard |
| CRITICAL | Shell interpolation could execute filenames as code; fallback checks could masquerade as SHA-256 | TermuxOperationExporter.kt, TermuxScriptBuilder.kt, assets/termux/manifest_executor.py, scripts/termux_manifest_executor.py | Embedded JSON data, mandatory Python hashlib and verified publication |
| CRITICAL | Heuristic ONNX outputs contributed to automatic semantic claims | OnDeviceAiClassifier.kt, LabelCategoryMapper.kt, SettingsScreen.kt | Real SDK inference by default; diagnostic ONNX always requires review |
| HIGH | Mixed Python/Kotlin source prevented compilation; wrapper missing | TermuxOperationExporter.kt, gradlew, gradlew.bat, gradle/wrapper | Real clean build; official checksummed wrapper |
| HIGH | Imported CLIP output dimensions/text representation did not match | OnDeviceAiClassifier.kt, SettingsRepository.kt | Unsupported import removed from active UI; honest fallback |
| HIGH | Metadata/deletion failure could be reported as successful; SAF reads can transcode | ExifAndTimestampManager.kt, OriginalMediaAccess.kt, SafeFileMover.kt, AndroidManifest.xml | Separate filesystem readback, checked deletion result, original-byte requirement |
| HIGH | Filename/size/time fingerprints could reuse another file's prediction | MediaRepository.kt, Entities.kt, Daos.kt | URI, original SHA-256, settings/category signature and source session scope |
| HIGH | APK ELF inspection found 4 KB-aligned 64-bit ONNX Runtime libraries | gradle/libs.versions.toml, scripts/inspect_apk.py | Upgrade runtime to 1.22.0 and require 16 KB alignment for packaged 64-bit libraries |
| MEDIUM | Thumbnail-like sampling, repeated frames, score inflation, resource cleanup | VideoFrameAnalyzer.kt, VideoPredictionAggregator.kt, VisualFeatureExtractor.kt | Bounded distinct native frames, conservative aggregation, coroutine cancellation and recycle |
| MEDIUM | UI-driven long jobs and incomplete checkpoints/cancellation | MediaAnalysisWorker.kt, MediaTransferWorker.kt, WorkerNotifications.kt, MainViewModel.kt | Unique foreground work, serialized settings snapshots, persistent pause and per-item/session checkpoints |
| MEDIUM | Dead mode/export controls, hidden cache outputs, misleading score/error text | Compose screens, MainViewModel.kt, localized resources | Functional mode controls/cancel/save ZIP, errors shown, honest model labels and bilingual defaults |
| MEDIUM | Provider revocation/listing failures silently looked like an empty folder | StorageScanner.kt | Stream traversal and explicit access failures; cancellation; bounded buffering |
| LOW | Stale README claims and missing executable QA | README.md, tests/, androidTest/, workflow | Actual supported behavior, isolated fault tests and repeatable verification |

All Kotlin paths above are under app/src/main/java/com/aimediasorter/app, except files explicitly identified as build/assets/tests.

## Test Strategy and Limitations

JVM tests exercise the production transfer engine and real temporary filesystem files: integrity, historical mtime, safe move, concurrent filename collision, same-size corruption, changed source, destination mutation after checkpoint, missing identities, deletion failure, cancellation, path traversal and a provider returning the source as staging.

Python tests exercise the production executor in isolated temporary folders: dry run, Unicode/quotes/metacharacters, actual move/copy, collision policies, checksum mismatch, review restrictions, path confinement and timestamp fallback. The generated shell is syntax checked and executed by the JVM test against its embedded production Python.

Android instrumentation runs the actual bundled ML Kit and diagnostic ONNX runtimes, native multiframe/corrupt-video decoding, panorama/mirror preprocessing, 2,002-file discovery, Room source scoping and migration, original MediaStore stream equality and a test-only DocumentsProvider with collision/corruption/revocation/timestamp faults.

The test provider is packaged only in the instrumentation APK. No destructive test uses a real user archive. Tests with an owned MediaStore sample do not prove every OEM SAF grant/redaction combination.

Remaining release validation:
1. Representative labeled photos/videos, multi-subject annotations and threshold/unknown evaluation.
2. Physical mid-range devices: peak RAM, thermal throttling, throughput, battery, provider behavior and very large collections.
3. OEM/SD-card providers, original-byte grants, HEVC/HDR passthrough and 16 KB page-size device execution.
4. Interrupted process/device power loss across every transfer checkpoint; recovery is conservative but not a full transactional filesystem.
5. Model/asset provenance, dependency terms and release-signing/distribution policy.
6. Custom open-vocabulary models are not implemented; no accuracy or model capability is fabricated.

## Final Verification Record

Pending final CI result for the proposed branch. This section will be updated with exact commit/run identifiers and executed test results before handing over the pull request.
