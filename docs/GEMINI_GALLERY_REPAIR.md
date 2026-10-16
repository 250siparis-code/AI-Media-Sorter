# Gemini repair and virtual gallery review

## Scope and provenance
Repository: 250siparis-code/AI-Media-Sorter. Branch: codex/gemini-gallery-repair. Pull request: #5, stacked on unmerged #4 (codex/gemini-byok). No main updates or automatic merges.
This phase explicitly supersedes the earlier selectable Offline/Hybrid requirement: Gemini is the only active production classifier.

## P0: exact Save failure
The Compose Save button, GeminiKeyVault.save, and GeminiHttpTransport.request each used the pattern `[A-Za-z0-9_-]{20,256}`. It rejects the period in `AQ.` authorization keys, and rejects longer complete keys. The UI disabled Save before the click handler could run. Even fixing only the UI would leave vault/transport rejection.
The click handler also cleared the input before asynchronous persistence completed. Save exceptions went to a generic export snackbar instead of the key card.
ApiKeyInput now centralizes bounded printable ASCII validation (20–4096 characters, trimmed outer whitespace, no internal whitespace/control characters, no required prefix). This validates safe input transport, not credentials.
Save/Replace use Android Keystore AES-GCM and AtomicFile in noBackupFilesDir. Success is acknowledged after encrypted readback; input is retained on failure. Only successful readback acknowledgement clears focus and hides the keyboard so the result is visible. The credential epoch advances before atomic publication so a crash cannot publish a changed key under an old consent epoch. Key actions and settings refresh are serialized. Every vault instance in the app process shares a lock keyed by the encrypted file's canonical path: AtomicFile provides no locking, and openRead can remove an unfinished concurrent replacement. Reads, snapshots, revision changes, writes and deletion use this lock. A concurrent replacement regression test protects this boundary. Delete revokes consent and removes the encrypted record and alias.
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
This is a personal BYOK client. Keystore encryption protects storage at rest; a rooted/compromised device, malicious keyboard or process instrumentation can still expose a decrypted key. It is not equivalent to a server-held credential. Deleting the local key does not revoke it at Google. Google account/service-tier data terms apply to uploaded images.

Every personal-media batch requires an unchecked-by-default upload consent. Reanalysis can be scoped to at most 100 explicitly selected media IDs, bound into the consent signature; only those IDs are uploaded. Initial analysis is scoped to the user-selected directory and stated traversal options. The consent signature binds the source URI, recursion, media filter and extensions as well as model/category/credential configuration; a grant for one directory cannot authorize another.
Defaults: at most 12 generation attempts per batch, 100 per UTC day, one retry and 15 seconds between generation attempts. The interval is configurable (0–120 seconds); Google quotas vary by model and account. Durable accounting reserves attempts before sending, including retries. Cancellation interrupts pacing and I/O.
Both USD estimates can remain zero for Free Tier workflows; request limits still apply. Local estimates are not Google prices, a paid/free-tier detector, or a provider-enforced billing cap. The app never enables billing; an already billable Google project can incur charges.
Cache keys bind original SHA-256, model, category descriptions, preprocessing protocol, output settings and credential epoch. Successful results have seven-day freshness. Explicit reanalysis bypasses cache. Durable same-batch uncertain-request tombstones prevent silent replay after process death. New consent is required to retry an uncertain outcome. A fresh normal scan resumes pending files while reusing prior valid results.

## Physical safety
Analysis and virtual correction create no destination directories and perform no copies, moves, overwrites or source deletion. Source selection and scanning no longer require a destination; the outside-source destination check applies only to physical organization.
A separate Copy/Move choice builds a no-write preview. An unchecked-by-default physical confirmation approves exactly the displayed destination steps, source fingerprint and decision version. At most 100 destination operations are accepted per batch.
For multiple categories, COPY creates separate verified copies and retains the source. MOVE requires the user to choose the one destination receiving the original; additional copies run first. Every preceding copy is rehashed before the final move and again at its source-deletion checkpoint.
The existing VerifiedTransferEngine, SafeFileMover, OriginalMediaAccess and timestamp implementation remain unchanged. SHA-256 readback, collision handling, original bytes, separate filesystem/embedded timestamps, mtime warnings, cancellation and durable journals remain in force. Source deletion requires a verified final destination and the original engine's durability/timestamp conditions.
Matching approved/completed/recovery plans are rejected rather than silently producing another copy. Stale decisions, revoked organization permission, changed bytes/settings/categories, failed prior copies and interrupted operations stop execution. Recovery records uncertainty and never resumes source deletion automatically. Cancel explicitly revokes unstarted plans and sets a durable cancellation request for an active plan; the deletion checkpoint rechecks this approval.

## Defects found and corrected during verification

- HIGH: AQ. keys were rejected by the same character class in the Compose Save enabled condition, GeminiKeyVault.save and GeminiHttpTransport.request. All three use the shared ApiKeyInput policy. Saving is independent of model selection and is distinct from authentication.
- HIGH: Per-instance synchronization did not protect independent vault instances sharing one AtomicFile. A concurrent read could discard a replacement. The canonical-file process lock protects the full credential/revision transaction; the application has no separately declared service process.
- MEDIUM: Generic 'billing' text previously overrode HTTP 429/5xx and suppressed quota retries. HTTP status now takes precedence; quota messages linking to billing help retain QUOTA and bounded retry/backoff. A mocked realistic response verifies reservations, delays, catalog state and server state.
- MEDIUM: Settings needed IME insets so key actions remain reachable while typing under edge-to-edge display.
- MEDIUM: Text-only navigation selectors, generic UiScrollable lookup and manually finishing Activities produced intermittent installed-UI failures. Tests now use stable action/viewport IDs, the official Compose instrumentation driver and ActivityScenario lifecycle synchronization. Empty-disabled, AQ-enabled, Save, Activity recreation, Replace and Delete assertions remain.
- MEDIUM: The gallery smoke test could find a thumbnail but fail to reach the full-image button in a shorter viewport. It now scrolls the actual review viewport and waits for bitmap decode in both the gallery and full-screen dialog. Original fixture bytes must remain identical.
- Release test boundary: DataStore and lifecycle APIs called from the separately packaged acceptance APK need preserved runtime contracts. The app-provided Compose UI/runtime contracts and lifecycle contracts join the existing DataStore/Room/Kotlin/coroutines rules. R8 and resource shrinking remain enabled. Mock response construction uses Android org.json rather than a serialization builder removed as unused by R8.
- Release verification specifically caught ComposeRootRegistry calling ViewTreeLifecycleOwner after R8 had removed that app-provided facade. Lifecycle owner/observer contracts are kept across the APK boundary; no test callback or assertion is disabled.
- Test diagnostics: Crash-buffer logcat is captured inside the failing emulator command before the runner terminates the device. A process crash is not dismissed as a passed or skipped test.
- The previous source screen required a destination even for analysis; initial scanning now requires only a source. Physical destination safety checks remain in the explicit operation preview. Category Upsert preserves membership links.

Implementation references:
- https://developer.android.com/reference/android/util/AtomicFile
- https://developer.android.com/guide/components/activities/testing
- https://developer.android.com/develop/ui/compose/testing/interoperability

Compose testing uses the existing BOM and real app Activities. A rule chain seeds isolated fixtures before Activity launch and cleans them only after complete Activity/ViewModel destruction. The key test waits separately for durable file publication and asynchronous UI acknowledgement (Compose idleness does not await unrelated IO), then closes and relaunches a fresh Activity/ViewModel, checks encrypted readback and user-visible Save success, and retains all enabled/disabled/Replace/Delete checks. Emulator jobs build their own test artifacts independently of the distributable-APK job, so both validations run concurrently without omitting any check.

## Executed verification

Verified application/test commit: `8ee4d1bbd456fcfacbeef485551f63666f012e11`. Builds and tests ran in GitHub Actions, not on a physical phone. [Complete successful run](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38938970906) and [independent PR run](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38938980751). Native logs were fetched and checked independently; all five configurations in both runs report 36 tests, zero failures/errors and one explicitly gated live-API skip.

- Clean Gradle build: `./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --stacktrace` — SUCCESS.
- JVM: **45 passed**, zero failures/errors/skips (five XML reports).
- Python: `python3 -m unittest discover -s tests -v` — **20 passed**.
- Separate ARM64 and ARMv7 debug/release-QA APK assembly: SUCCESS. R8 and resource shrinking remain enabled for release.

| Android configuration | Variant | Passed | Skipped | Failures/errors |
|---|---|---:|---:|---:|
| API 34 / 4 KB | R8 release-QA | 35 | 1 | 0 |
| API 35 / 16 KB | R8 release-QA | 35 | 1 | 0 |
| API 34 / 4 KB | debug | 35 | 1 | 0 |
| API 35 / 4 KB | debug | 35 | 1 | 0 |
| API 35 / 16 KB | debug | 35 | 1 | 0 |

That is 175 successful native test invocations per matrix; the single skipped case in each configuration requires explicit live API configuration. No UI or safety test is disabled. UI checks include AQ. input, empty-disabled/enabled Save, encrypted readback, nonempty localized feedback, complete Activity/ViewModel relaunch, Replace/Delete and decoded thumbnail/full-screen rendering. Native Room/transfer tests verify one original with multiple memberships, immediate correction, no physical writes during analysis/virtual updates, explicit verified copies, copy-before-move, stale-plan blocking, cancellation, cache, retry scope and quota/resume.

APK manifests independently declare minSdk **34** / targetSdk **35**. All ARM64 native ELF load segments pass 16 KB alignment inspection. Existing bundled model assets have identical SHA-256 values to the measured baseline. Separate release artifact ZIPs were successfully downloaded through the GitHub connector.

Downloads (QA signed, for installation testing):

- [ARM64 optimized release-QA APK ZIP](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38938970906/artifacts/12062477216)
- [ARMv7 optimized release-QA APK ZIP](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38938970906/artifacts/12063016035)
- [Both debug APKs](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38938970906/artifacts/12062566964)
- [All four APKs](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38938970906/artifacts/12063031024)
- [JUnit, lint, size, manifest/ELF and DEX package reports](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38938970906/artifacts/12063016054)



## Measured APK size and contents

The actual baseline universal debug APK was downloaded from run 38682222910 and remeasured: **187562102 bytes / 178.873159 MiB**. These are APK bytes after extraction, not artifact ZIP sizes. Savings are against that universal baseline; current debug builds are already ABI-specific.

| APK | Bytes | MiB | Savings |
|---|---:|---:|---:|
| optimized-arm64-v8a-debug.apk | 35916222 | 34.252378 | 80.8510% |
| optimized-arm64-v8a-release-QA.apk | 19503960 | 18.600426 | 89.6013% |
| optimized-armeabi-v7a-debug.apk | 34272040 | 32.684364 | 81.7276% |
| optimized-armeabi-v7a-release-QA.apk | 18159738 | 17.318476 | 90.3180% |

| Entry group | Baseline raw bytes | Baseline compressed bytes | ARM64 R8 raw bytes | ARM64 R8 compressed bytes |
|---|---:|---:|---:|---:|
| native | 117827824 | 117827824 | 29321168 | 11050325 |
| dex | 65946988 | 65946988 | 12982856 | 4674914 |
| assets | 3116435 | 3108359 | 3126500 | 3067728 |
| resources | 443309 | 346624 | 652949 | 566596 |
| other | 93013 | 58265 | 114949 | 68320 |

Baseline native raw bytes by ABI: arm64-v8a 29321168; armeabi-v7a 20255908; x86 34133916; x86_64 34116832.

Across those four baseline ABIs, ONNX native libraries total 75206864 raw bytes and ML Kit pipeline libraries 42560668 raw bytes. Current ARM64 compressed ONNX entries total 6589795 bytes; ML Kit pipeline is 4453514 bytes. ZIP/signing/header overhead for this release is 76077 bytes.

Bundled asset sizes: ML Kit model 3042638 bytes; diagnostic ONNX graph 10041 bytes; diagnostic vocabulary 51613 bytes. All three SHA-256 values are unchanged. The small graph is not claimed to be a semantic classifier. No smaller ONNX distribution or operator-reduced build is substituted without validating retained diagnostics and ABI/page-size coverage.

Baseline apkanalyzer defined-code bytes: Compose 20728703; WorkManager 292519; Room 118983; Kotlin 956954; kotlinx 723809; ONNX Java package 67890; ML Kit Java package 81263; Google Play services 1534722. These are measured package metrics, not additive compressed dependency sizes: DEX metadata/tables are shared. Full before/current package listings and every native/asset entry are in verification-reports.

The size reduction preserves per-ABI builds, compressed native/DEX packaging and release R8/resource shrinking. Shared UI/runtime/lifecycle contracts are retained so the independently packaged release acceptance suite uses the actual minified app. This increases the retained code footprint compared with a build lacking those tested runtime contracts; Material/Foundation/Navigation and unrelated code still shrink. No AI model is deleted. Download size does not establish installed/extracted size, RAM, speed or battery use. ARMv7 is for compatible 32-bit devices; 16 KB runtime/ELF claims apply to ARM64 and the x86_64 test target.

APK SHA-256 values:

- optimized-arm64-v8a-debug.apk: `06f0569f517ed80bf964645b90b8cc66742c00f0df87f7510e262d3b7fe07302`
- optimized-arm64-v8a-release-QA.apk: `76f16e8135b4d38cbaec45875e94f662d4dab28e374889f93d1c064d5f7582be`
- optimized-armeabi-v7a-debug.apk: `85bbf563490a64499247c2e875afd4254e4c438d650d6d762aceaba6c6b598fe`
- optimized-armeabi-v7a-release-QA.apk: `dc279746f25f1b5680401830e5aaa3d7d58b693c6a5290de90bbe9e8460cc394`

## Tests and acceptance limits
Automated suites include mocked JSON/HTTP policy, API input validation, Room migration and membership, no-file-write virtual correction, explicit multi-copy and copy-before-move, stale-preview rejection, cache/reanalysis scope, quota pending/resume, Keystore reopen/encryption/delete, and installed Compose Save/Replace/Delete through actual Compose controls. Existing safety, decoder, original-byte, timestamp, recovery, JVM and Python tests remain.
The completed runs and artifact links above are the executed evidence. Source inspection alone is not a runtime or accuracy pass.
The local attachment search found two logo JPEGs only. The ten illustrative evaluation photographs and labeled 36-photo collection are not available to this test process. No semantic accuracy result or improvement is claimed.
No live user-credential call or physical-device test has been performed. Emulator tests and synthetic fixtures cannot establish actual photographic accuracy, account quota eligibility, physical ARM/OEM behavior or all SAF-provider behavior.
APKs use QA/debug signing for installation testing, not production signing. Stable distribution signing and an in-place upgrade from the user's installed signing identity have not been verified; hosted-runner debug signing identities may differ. Android minSdk 34, compile/target 35, R8/resource shrinking, the official launcher icon, separate ARMv7/ARM64 artifacts and Android 15 4 KB/16 KB CI configurations are preserved.

## Phone-only acceptance steps
1. Install the appropriate QA APK from the Actions artifact ZIP; no Android Studio is needed.
2. In Settings, paste the complete key, including AQ. if issued. Save before selecting a model. Verify the unverified saved message. Restart the app and verify the key remains saved. Replace/Delete are separate actions.
3. Tap Test connection / Load models. Select a returned candidate. Tap Test selected model; this sends only a synthetic tile and consumes one generation attempt. Check the specific success/failure state.
4. Choose an isolated media folder first. Set an appropriate request limit for the selected collection and account quota. Approve only the intended upload batch.
5. Browse virtual category folders, Needs Review, Analysis Failed and Pending. Open images full screen. Multi-subject suggestions must appear in every suggested gallery and in review with one original media row.
6. Correct or accept one/multiple virtual categories. Confirm no physical directories or copies were created.
7. Set a destination outside the source and explicitly enable Organize Mode in Settings. Choose Copy or Move, inspect the exact plan, choose a single MOVE destination where necessary, then explicitly approve physical execution. Start with isolated disposable copies of test media.
8. Inspect SHA-256/timestamp reports and retained-source warnings. Compare the ten supplied expectations against genuine Gemini responses only when those actual photographs are available and uploaded with user consent.

## Final assessment

Automated verification supports the installed key lifecycle, validated mocked Gemini pipeline, virtual gallery/storage, safe explicit physical plans, native decoding and optimized APK compatibility under the tested emulator configurations. Live credential authentication, the user's model catalog, genuine photographic accuracy, physical ARM/OEM execution and every SAF provider remain unverified. The ten reference files and labeled 36-photo collection were not available; no examples or accuracy improvement are claimed as passed. A QA signing key is not production distribution signing, and in-place upgrade from an existing signing identity is unverified. Complete the phone-only acceptance steps before using real originals for MOVE.

## Changed files

- `.github/workflows/android-build.yml`
- `README.md`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/src/androidTest/java/com/aimediasorter/app/CachedMetadataTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/CloudNativeTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/DatabaseMigrationTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/GeminiSettingsUiTest.kt`
- `app/src/androidTest/java/com/aimediasorter/app/InstalledActivityRule.kt`
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
- `app/src/main/java/com/aimediasorter/app/ui/screens/SourceDestinationScreen.kt`
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
- `scripts/run_native_tests.sh`
