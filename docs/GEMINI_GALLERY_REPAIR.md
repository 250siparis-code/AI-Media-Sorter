# Gemini repair and virtual gallery review

## Scope and provenance
Repository: 250siparis-code/AI-Media-Sorter. Branch: codex/gemini-gallery-repair. Pull request: #5, stacked on unmerged #4 (codex/gemini-byok). No main updates or automatic merges.
This phase explicitly supersedes the earlier selectable Offline/Hybrid requirement: Gemini is the only active production classifier.

## P0: exact Save failure
The Compose Save button, GeminiKeyVault.save, and GeminiHttpTransport.request each used the pattern `[A-Za-z0-9_-]{20,256}`. It rejects the period in `AQ.` authorization keys, and rejects longer complete keys. The UI disabled Save before the click handler could run. Even fixing only the UI would leave vault/transport rejection.
The click handler also cleared the input before asynchronous persistence completed. Save exceptions went to a generic export snackbar instead of the key card.
ApiKeyInput now centralizes bounded printable ASCII validation (20–4096 characters, trimmed outer whitespace, no internal whitespace/control characters, no required prefix). This validates safe input transport, not credentials.
Save/Replace use Android Keystore AES-GCM and AtomicFile in noBackupFilesDir. Success is acknowledged after encrypted readback; input is retained on failure. The credential epoch advances before atomic publication so a crash cannot publish a changed key under an old consent epoch. Key actions and settings refresh are serialized. Delete revokes consent and removes the encrypted record and alias.
A saved key is explicitly labeled unverified. No actual user key was supplied to this development environment or written to source, exports, logs, WorkData, or assets.

## Connection and model verification
The explicit Test connection / Load models action uses the official HTTPS v1beta models endpoint and x-goog-api-key header. Pagination is bounded; malformed catalogs are failures. There is no prefix-based routing or billing activation.
Only returned generateContent candidates are selectable; known audio/live/TTS-only variants are excluded, and IMAGE input metadata is respected when provided. The catalog does not universally prove image/JSON capability.
Therefore a second explicit Test selected model action sends one synthetic color tile using the actual structured classification schema. Its successful capability result is persisted for that credential epoch/model for seven days and gates personal-media uploads. It uses the same daily limits, one-request batch accounting, and pacing. It never sends a personal photo.
Authentication, model availability, HTTP 400, quota, billing, server, malformed response, timeout and network failures have sanitized states. Provider response bodies are never shown or logged.
Official references, accessed 2026-10-16:
- https://ai.google.dev/gemini-api/docs/api-key
- https://ai.google.dev/api/models
- https://ai.google.dev/api/generate-content
- https://ai.google.dev/gemini-api/docs/structured-output

## Genuine Gemini analysis
MediaRepository no longer constructs or calls ML Kit, ONNX or offline video classifiers. Old enum settings are decoded and normalized to GEMINI. The merged ML Kit startup provider is explicitly removed; legacy tests initialize the SDK themselves. Legacy classifiers/dependencies remain dormant for regression diagnostics; there is no automatic fallback.
Original-byte SHA-256 is computed locally. Photo input is decoded with bounds, orientation/mirroring corrected, downscaled preserving aspect to at most 512 pixels, JPEG encoded without EXIF/GPS, and held in memory. Source media is not rewritten.
Video uses actual MediaMetadataRetriever decoding at requested 10/50/90 percent offsets, distinct-frame hashing, up to three frames, and a combined request asking Gemini to aggregate independently important subjects across samples. Less than two distinct decoded frames requires review. This is sampled-frame analysis, not complete-video understanding.
The validated JSON contains categories, subjects and importance, pose, scene, details, reason, and review_status. Only configured category IDs are accepted. Unexpected fields, unsupported values, duplicates, missing/malformed data, blocked or truncated output fail closed. No numerical confidence is requested or fabricated.
There is no filename rule, fixed category precedence, or application-side subject-detection heuristic. Two or more categories are preserved and routed deterministically to NEEDS_REVIEW. One unambiguous category becomes a PROPOSED virtual assignment; ambiguous/empty choices remain reviewable. API failures are FAILED, and local budget/consent exhaustion is PENDING, never a guessed Other result.

## Virtual storage and visual review
Room schema 3 adds:
- media_category_links(mediaId,categoryId,origin), composite primary key and cascading foreign keys. PROPOSED links preserve Gemini suggestions; EFFECTIVE links drive virtual galleries.
- media analysisStatus, analysisModel, analysisJson and decisionVersion.
- physical_plans, storing separately approved physical operations.
Explicit 1→2→3 migrations preserve existing media and journals; legacy results require review. Virtual updates occur in one Room transaction and never call file-transfer code. Multiple virtual appearances point to one original media row.
Folder cards show counts, review counts and a representative real thumbnail. Lazy adaptive grids use asynchronous bounded Coil requests, stable IDs and content-hash cache keys. Memory cache is bounded to 15% of available heap; private local thumbnails are not persisted to disk by this loader. Permission failures have a visible placeholder.
Users can inspect a full-screen, bounded-resolution zoomable preview, view suggestions and descriptions, select multiple categories, replace categories, defer review, exclude physical operations, or explicitly reanalyze. Changes invalidate old physical previews through decisionVersion and update Room flows immediately. Original video can be opened in an external player.
Reports/advanced results and source/category management remain reachable.

## Consent, quota and recovery
Every personal-media batch requires an unchecked-by-default upload consent. Reanalysis can be scoped to at most 100 explicitly selected media IDs, bound into the consent signature; only those IDs are uploaded. Initial analysis is scoped to the user-selected directory and stated traversal options.
Defaults: at most 12 generation attempts per batch, 100 per UTC day, one retry and 15 seconds between generation attempts. The interval is configurable (0–120 seconds); Google quotas vary by model and account. Durable accounting reserves attempts before sending, including retries. Cancellation interrupts pacing and I/O.
Both USD estimates can remain zero for Free Tier workflows; request limits still apply. Local estimates are not Google prices, a paid/free-tier detector, or a provider-enforced billing cap. The app never enables billing; an already billable Google project can incur charges.
Cache keys bind original SHA-256, model, category descriptions, preprocessing protocol, output settings and credential epoch. Successful results have seven-day freshness. Explicit reanalysis bypasses cache. Durable same-batch uncertain-request tombstones prevent silent replay after process death. New consent is required to retry an uncertain outcome. A fresh normal scan resumes pending files while reusing prior valid results.

## Physical safety
Analysis and virtual correction create no destination directories and perform no copies, moves, overwrites or source deletion.
A separate Copy/Move choice builds a no-write preview. An unchecked-by-default physical confirmation approves exactly the displayed destination steps, source fingerprint and decision version. At most 100 destination operations are accepted per batch.
For multiple categories, COPY creates separate verified copies and retains the source. MOVE requires the user to choose the one destination receiving the original; additional copies run first. Every preceding copy is rehashed before the final move and again at its source-deletion checkpoint.
The existing VerifiedTransferEngine, SafeFileMover, OriginalMediaAccess and timestamp implementation remain unchanged. SHA-256 readback, collision handling, original bytes, separate filesystem/embedded timestamps, mtime warnings, cancellation and durable journals remain in force. Source deletion requires a verified final destination and the original engine's durability/timestamp conditions.
Matching approved/completed/recovery plans are rejected rather than silently producing another copy. Stale decisions, revoked organization permission, changed bytes/settings/categories, failed prior copies and interrupted operations stop execution. Recovery records uncertainty and never resumes source deletion automatically. Cancel explicitly revokes unstarted plans and sets a durable cancellation request for an active plan; the deletion checkpoint rechecks this approval.

## Tests and acceptance limits
Automated suites include mocked JSON/HTTP policy, API input validation, Room migration and membership, no-file-write virtual correction, explicit multi-copy and copy-before-move, stale-preview rejection, cache/reanalysis scope, quota pending/resume, Keystore reopen/encryption/delete, and installed Compose Save/Replace/Delete via UI Automator. Existing safety, decoder, original-byte, timestamp, recovery, JVM and Python tests remain.
CI results and artifact links are recorded after execution; source inspection alone is not a pass.
The local attachment search found two logo JPEGs only. The ten illustrative evaluation photographs and labeled 36-photo collection are not available to this test process. No semantic accuracy result or improvement is claimed.
No live user-credential call or physical-device test has been performed. Emulator tests and synthetic fixtures cannot establish actual photographic accuracy, account quota eligibility, physical ARM/OEM behavior or all SAF-provider behavior.
APKs use QA/debug signing for installation testing, not production signing. Android minSdk 34, compile/target 35, R8/resource shrinking, the official launcher icon, separate ARMv7/ARM64 artifacts and Android 15 4 KB/16 KB CI configurations are preserved.

## Phone-only acceptance steps
1. Install the appropriate QA APK from the Actions artifact ZIP; no Android Studio is needed.
2. In Settings, paste the complete key, including AQ. if issued. Save before selecting a model. Verify the unverified saved message. Restart the app and verify the key remains saved. Replace/Delete are separate actions.
3. Tap Test connection / Load models. Select a returned candidate. Tap Test selected model; this sends only a synthetic tile and consumes one generation attempt. Check the specific success/failure state.
4. Choose an isolated media folder first. Set an appropriate request limit for the selected collection and account quota. Approve only the intended upload batch.
5. Browse virtual category folders, Needs Review, Analysis Failed and Pending. Open images full screen. Multi-subject suggestions must appear in every suggested gallery and in review with one original media row.
6. Correct or accept one/multiple virtual categories. Confirm no physical directories or copies were created.
7. Choose Copy or Move, inspect the exact plan, choose a single MOVE destination where necessary, then explicitly approve physical execution. Start with isolated disposable copies of test media.
8. Inspect SHA-256/timestamp reports and retained-source warnings. Compare the ten supplied expectations against genuine Gemini responses only when those actual photographs are available and uploaded with user consent.

## Changed files
- `app/build.gradle.kts`
- `app/src/androidTest/java/com/aimediasorter/app/CachedMetadataTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/CloudNativeTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/DatabaseMigrationTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/GeminiSettingsUiTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/NativePipelineTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/VirtualGalleryNativeTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/VirtualGalleryUiTest.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/aimediasorter/app/AIMediaSorterApp.kt`
- `app/src/main/java/com/aimediasorter/app/data/local/AppDatabase.kt`
- `app/src/main/java/com/aimediasorter/app/data/local/CloudStateStore.kt`
- `app/src/main/java/com/aimediasorter/app/data/local/Daos.kt`
- `app/src/main/java/com/aimediasorter/app/data/local/Entities.kt`
- `app/src/main/java/com/aimediasorter/app/data/model/MediaModels.kt`
- `app/src/main/java/com/aimediasorter/app/data/preferences/GeminiKeyVault.kt`
- `app/src/main/java/com/aimediasorter/app/data/preferences/SettingsRepository.kt`
- `app/src/main/java/com/aimediasorter/app/data/repository/MediaRepository.kt`
- `app/src/main/java/com/aimediasorter/app/domain/cloud/ApiKeyInput.kt`
- `app/src/main/java/com/aimediasorter/app/domain/cloud/CloudAnalysisService.kt`
- `app/src/main/java/com/aimediasorter/app/domain/cloud/CloudProtocol.kt`
- `app/src/main/java/com/aimediasorter/app/domain/cloud/GeminiHttpTransport.kt`
- `app/src/main/java/com/aimediasorter/app/domain/storage/OperationPlanner.kt`
- `app/src/main/java/com/aimediasorter/app/ui/navigation/AIMediaSorterNavHost.kt`
- `app/src/main/java/com/aimediasorter/app/ui/navigation/ScreenRoutes.kt`
- `app/src/main/java/com/aimediasorter/app/ui/screens/CloudSettingsCard.kt`
- `app/src/main/java/com/aimediasorter/app/ui/screens/MediaReviewScreen.kt`
- `app/src/main/java/com/aimediasorter/app/ui/screens/PhysicalOperationsScreen.kt`
- `app/src/main/java/com/aimediasorter/app/ui/screens/SettingsScreen.kt`
- `app/src/main/java/com/aimediasorter/app/ui/screens/VirtualGalleryScreen.kt`
- `app/src/main/java/com/aimediasorter/app/ui/viewmodel/MainViewModel.kt`
- `app/src/main/java/com/aimediasorter/app/worker/MediaAnalysisWorker.kt`
- `app/src/main/java/com/aimediasorter/app/worker/MediaTransferWorker.kt`
- `app/src/main/res/values-tr/strings.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/aimediasorter/app/ApiKeyInputTest.kt`
- `app/src/test/java/com/aimediasorter/app/CloudProtocolTest.kt`
- `docs/GEMINI_BYOK.md`
- `docs/GEMINI_GALLERY_REPAIR.md`
