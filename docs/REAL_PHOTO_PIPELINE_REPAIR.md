# Real-photo Gemini pipeline repair and QA

## Scope and evidence boundary

Repository: https://github.com/250siparis-code/AI-Media-Sorter
Base branch: `codex/gemini-gallery-repair`, commit `41f2ae555967d842c3f1269c230d44d3b7cdcfda`.
Repair branch: `codex/real-photo-pipeline-repair`.
Pull request: https://github.com/250siparis-code/AI-Media-Sorter/pull/6 (unmerged).

The device report establishes that credentials, catalog retrieval and synthetic model tests worked, but 36 real JPGs displayed “Analysis Failed.” Those observations are user-reported physical-device evidence. No device database, per-file error trace, actual photographs or provider response records from that run were supplied.

The old APK did not record per-file dispatch counts or precise failure stages. Consequently, the exact HTTP outcome of each of those historical 36 files cannot be reconstructed. This report distinguishes a reproduced application defect from an unsupported claim that every historical failure necessarily had the same cause. No live Google batch or personal media operation was performed during this repair.

## Confirmed defects and root cause

| Severity | Defect | Evidence and correction |
|---|---|---|
| HIGH | Analysis required the strict unredacted-original transfer accessor. | `MediaRepository.executeMediaScanAndAnalysis` attempted an original-byte checksum; `CloudAnalysisService.analyze` required that hash; `CloudMediaEncoder.images` reopened through `OriginalMediaAccess`. A selected JPEG whose pixels were readable could therefore stop before HTTP because unredacted original access was unavailable. Analysis now uses selected-URI reads and, when needed, a separate analysis fingerprint. |
| MEDIUM | “Analysis Failed” did not identify a failure stage or even prove the stored status was FAILED. | The old `failed()` helper also used that category label for CONSENT/BUDGET results stored as PENDING. Generic exception handling additionally collapsed preprocessing and parsing failures. New finite diagnostic codes and independent outcome counts preserve the distinction. |
| MEDIUM | Every progress card displayed the video sampling explanation. | `AnalysisProgressScreen` rendered `progress_video_sampling` unconditionally. JPG routing itself uses `candidate.isVideo=false`; that text was not evidence of video decoding. The explanation now appears only for a successfully analyzed/cached video, independently of the error display. |

Android documents that [`MediaStore.setRequireOriginal`](https://developer.android.com/reference/android/provider/MediaStore#setRequireOriginal(android.net.Uri)) may reject access when original contents cannot be provided, including when ACCESS_MEDIA_LOCATION is unavailable. The manifest declared that permission, while the activity requested notifications only. Selected-media pixel analysis must not depend on obtaining unredacted GPS-bearing original bytes.

### Before/after reproduction

The same `RealPhotoPipelineTest.readableJpegMustReachAnalysisEvenWhenUnredactedOriginalAccessIsDenied` is retained across the repair:

- A real, public photographic JPEG is readable normally; its ordinary-file control reaches the mocked Gemini endpoint and validates a structured response.
- A provider models Android's original-content permission rejection while leaving the selected JPEG readable.
- Before repair, the expected `(PROPOSED, 1 mock request)` was actually `(FAILED, 0 requests)`.
- The failure reproduced on all five Android configurations, including API 34, R8 release and 16 KB API 35: [reproduction run 38947459950](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38947459950), commit `0085203d5fc4790d6aa8e2f922a3a94e23387c2f`.
- The unchanged regression passes in all five configurations after repair. A separate real Android MediaProvider test creates an isolated, public JPEG owned by the test APK, grants selected read access to the application, confirms strict original access is rejected, and verifies selected decoding/fingerprinting still works. Its temporary fixture is cleaned up.

The original accessor remains strict. The repair does not grant broad storage access, weaken original-byte verification, substitute an analysis fingerprint into `sha256Checksum`, or make an unreadable original eligible for transfer.

## Request comparison and pipeline

| Aspect | Synthetic selected-model test | Real JPG analysis |
|---|---|---|
| Transport | Same production Gemini client/HTTP builder | Same |
| Endpoint | Official HTTPS v1beta selected-model `:generateContent` | Same selected model |
| Credential | Header-only `x-goog-api-key` | Same; no URL or body credential |
| Content type | `application/json` | Same |
| Image | 32×32 synthetic JPEG | Selected photo decoded with sampling, oriented, bounded to a 512-pixel long edge and JPEG quality 85 |
| Inline MIME | `image/jpeg` | Same; encoder checks JPEG start/end markers |
| Photo frame timestamps | None | None |
| Categories | One synthetic `test_scene` category | Enabled configured IDs/descriptions; nine defaults in the regression |
| System instruction/schema | Existing visual-subject instruction and validated schema | Same, with configured category enum substituted |
| Output token limit | Existing configured limit | Same configured limit; no change or automatic increase |
| Local prerequisites | Vault/catalog/model verification and quotas | Additionally selected-media readability, identity checks, consent and cache lookup |

The automated comparison checks the production service's synthetic and real-photo requests, including a byte-for-byte generation-configuration/schema comparison after substituting the configured category IDs. It decodes the actual inline Base64 JPEG and checks dimensions/MIME. HTTP construction tests inject an in-memory connection into the production transport and verify the official origin/path, method, header-only credential, redirect policy and unchanged 10/30-second connect/read timeouts.

The complete request-builder source (4,516 characters, including prompts/schema) and CloudOptions definitions were compared against the base and are byte-identical. Semantic prompts, configured categories, review-routing rules, selected models, confidence handling and output-token settings are unchanged. The protocol cache version only gains a selected-URI preprocessing identity marker. MAX_TOKENS is diagnosed rather than silently repaired by guessing or changing the model.

A valid single-category result remains proposed; multiple categories remain Needs Review. A readable selected image with unavailable original-byte access can be analyzed and shown virtually, but retains review/transfer-blocking state until original-byte safety prerequisites can be met.

## Diagnostics and accurate summaries

Per-file Room fields are `diagnosticCode`, `apiRequestsSent`, `requestReservations` and `responseHttpStatus`. Model/time and validated analysis metadata remain in their existing fields. Room migrates 3→4 explicitly; cloud state migrates 2→3 explicitly and preserves catalog, grants, quota counters, cache and verification records.

Representative distinctions:

| Stage | Codes |
|---|---|
| Local input/preprocessing | LOCAL_SOURCE_ACCESS, LOCAL_SOURCE_CHANGED, LOCAL_IMAGE_DECODE, LOCAL_JPEG_ENCODE, LOCAL_VIDEO_DECODE |
| Unsent/local control | CONFIG_NOT_READY, CONSENT_INVALID, LIMIT_REACHED, ATTEMPT_UNCERTAIN, REQUEST_BUILD |
| Transport | NETWORK, TIMEOUT |
| HTTP | HTTP_REQUEST, HTTP_AUTH, HTTP_BILLING, HTTP_MODEL, HTTP_QUOTA, HTTP_SERVER, HTTP_UNEXPECTED |
| Provider envelope/finish | RESPONSE_TOO_LARGE, RESPONSE_ENVELOPE, RESPONSE_FINISH, RESPONSE_MAX_TOKENS, RESPONSE_BLOCKED |
| JSON/validation | RESPONSE_JSON, RESPONSE_SCHEMA, RESPONSE_CATEGORIES |
| Successful reuse | SUCCESS, CACHE_HIT |
| Historic/unexpected | UNRECORDED, INTERNAL |

The summary separately reports Successful, Needs Review, Failed, Pending and API requests actually sent. Failed/pending files are excluded from successful/review counts; “processed” means the pipeline examined the file, not that classification succeeded. Completed batches with failures or pending files are labeled accordingly. Cached results and local quota skips do not add new sent requests.

**Counter definition:** a sent POST is counted only after the production transport writes/closes the complete request body. It is not confirmation of Google receipt, completed generation or billing. A network failure before that point can still involve a partial upload. Reservation counts deliberately remain separate and are charged conservatively before waiting/uploading. Confirmed send counts are persisted independently of successful parsing/cache publication.

Historic counts are `-1`/UNRECORDED and displayed as unknown. Old ambiguous “uncertain” totals are not relabeled as exact new outcome counts. New completed session summaries persist across activity/application restart.

Diagnostics use finite static messages translated into English and Turkish. No logging or remote diagnostic endpoint was added. Raw provider error bodies, secrets, URI/path strings, media contents and filenames are not interpolated into diagnostic messages. Personal filenames remain local gallery display data; no filename is added to the Gemini request.

## Safety and preserved behavior

These source files are unchanged from the base: `OriginalMediaAccess.kt`, `VerifiedTransferEngine.kt`, `SafeFileMover.kt`, `LocalTransferStorage.kt`, and `ExifAndTimestampManager.kt`.

Analysis and virtual corrections perform no physical organization. Original SHA-256 remains separate from a selected/redacted analysis fingerprint. A dedicated regression analyzes a readable selected document, verifies virtual category links, confirms that the original checksum is unavailable, and proves that even manual category selection cannot authorize a MOVE without original-byte verification.

Existing safe COPY/MOVE, collision, cancellation/recovery, timestamp-reporting and source-retention tests remain enabled. ABI selection, Android 14 minimum, target/compile 35, launcher artwork, release shrinking and dormant bundled model assets remain preserved. No fallback engine is activated.

## Changed files

Main sources (under `app/src/main/java/com/aimediasorter/app/`):

- `domain/cloud/SelectedMediaAccess.kt`, `CloudMediaEncoder.kt`, `CloudAnalysisService.kt`: selected-media reads, orientation fallback, separate analysis identity and failure propagation.
- `domain/cloud/AnalysisDiagnostics.kt`, `CloudProtocol.kt`, `GeminiHttpTransport.kt`: finite codes, parsing-stage distinctions, send observation and testable HTTP builder.
- `data/local/CloudStateStore.kt`, `AppDatabase.kt`, `Entities.kt`: persistent counters, diagnostics and explicit migrations.
- `data/model/MediaModels.kt`, `data/repository/MediaRepository.kt`: carry diagnostics, keep original SHA separate and calculate exclusive outcome counts.
- `ui/screens/AnalysisDiagnosticUi.kt`, `AnalysisProgressScreen.kt`, `MediaReviewScreen.kt`, `VirtualGalleryScreen.kt`: sanitized per-file details, summary and conditional video information.
- `ui/viewmodel/MainViewModel.kt`: restore recorded summaries; preserve unknown historic counts.

Other files:

- `app/build.gradle.kts`: QA version 1.1.1, versionCode 4.
- `app/src/main/res/values/strings.xml`, `values-tr/strings.xml`: diagnostic/summary localization.
- `app/src/test/java/com/aimediasorter/app/CloudProtocolTest.kt`: stage-specific validation and reservation/send tests.
- `app/src/androidTest/java/com/aimediasorter/app/RealPhotoPipelineTest.kt`, `RealPhotoDiagnosticsTest.kt`, `TransportDiagnosticsTest.kt`, `AnalysisDiagnosticUiTest.kt`, `PlatformPhotoAccessTest.kt`: realistic photographic input, mock responses, production HTTP, UI and real Android permission tests.
- `CloudNativeTest.kt`, `DatabaseMigrationTest.kt`, `CachedMetadataTest.kt` in that test directory: persistence/migrations and a correctly marked manually approved cache fixture.
- `FixtureDocumentsProvider.java`: publish/clean up an isolated public JPEG for the platform permission test; instrumentation APK only.
- `app/src/androidTest/assets/real_photo_fruits.jpg`, `REAL_PHOTO_FIXTURE.md`, `OPENCV_LICENSE.txt`: pinned OpenCV 4.10.0 photographic fixture and license, instrumentation APK only.
- This report.

## Executed verification

Tested application/test commit: `10e7fb0bb38979a44f509acce2da739f8bcb5043`.
Primary push run: [38952318453](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38952318453) — **SUCCESS**, all six jobs.
Independent PR run: [38952325321](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38952325321) — **FAILURE on both attempts** for its API 35 / 16 KB / R8 job; the other four native configurations and build passed. See the open stability finding below..

| Check | Executed result |
|---|---|
| Clean debug build and instrumentation compilation | Passed |
| JVM unit tests | 48 passed, 0 failures/errors/skips |
| Python transfer tests | 20 passed |
| Android lintDebug | Passed |
| ARM64 and ARMv7 debug/release-QA packaging | Four signed QA/debug APKs built and inspected |
| API 34 / 4 KB / debug | 55 passed, 1 optional live test skipped |
| API 34 / 4 KB / R8 release-QA | 55 passed, 1 optional live test skipped |
| API 35 / 4 KB / debug | 55 passed, 1 optional live test skipped |
| API 35 / 16 KB / debug | 55 passed, 1 optional live test skipped |
| API 35 / 16 KB / R8 release-QA | 55 passed, 1 optional live test skipped |

Each native configuration executes 56 tests, with zero failures/errors in the primary run. The only skipped test is `optionalLiveGeminiProtocolRequiresExplicitOperatorConfiguration`; its upload argument was not enabled. The platform MediaStore test was executed, not skipped.

Executed commands are retained in [the workflow](https://github.com/250siparis-code/AI-Media-Sorter/blob/10e7fb0bb38979a44f509acce2da739f8bcb5043/.github/workflows/android-build.yml) and [native test runner](https://github.com/250siparis-code/AI-Media-Sorter/blob/10e7fb0bb38979a44f509acce2da739f8bcb5043/scripts/run_native_tests.sh): clean Gradle unit/lint/debug builds; Python unittest discovery; per-ABI release/debug builds; connectedDebugAndroidTest/connectedReleaseAndroidTest on the five configurations.

The APK inspector independently confirms minSdk 34, targetSdk 35, valid signing, zip alignment and 16 KB ELF load alignment for every ARM64 native library. All three bundled model asset SHA-256 values match the verified baseline. Photographic decoder fixtures and their license are absent from the distributable app assets.

R8 verification caught cross-APK test entry points removed during shrinking. Four narrow Compose/activity facades are now retained; fixture-only EXIF mutation uses platform APIs instead of retaining unused AndroidX write APIs. R8 and resource shrinking remain enabled. No test or assertion was removed to obtain a passing run.

### Open HIGH finding: intermittent API 35 / 16 KB runtime failure

The independent PR run did not reproduce the primary run's clean result reliably. Its first API 35 / 16 KB / release attempt aborted with `DataStoreImpl.getCoordinator` / `kotlin.Lazy` null access and a native ART interface-resolution crash. A single targeted rerun of that job, without changing code or removing assertions, also failed: `SettingsRepository$Keys.INSTANCE` resolution produced `NoSuchFieldError`, followed by another ART crash. The run did not complete its full planned suite. The logs also contain crashes in preinstalled Google processes; this is an observation, not proof that the application is uninvolved.

- [Initial failed job](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38952325321/job/117146510750)
- [Failed targeted rerun](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38952325321/job/117163904341)

This is **not resolved** by the JPEG access fix or by the passing primary run. No tests were disabled and no repeated retries were used to hide it. Its attribution between the system image/runtime, test boundaries and application/library concurrency requires further investigation. The current application already retains the data/preferences and DataStore classes in its shrinker rules, so a dependency upgrade or further keep-rule change is not claimed as a verified fix.

The downloadable packages below are diagnostic **QA builds only**. This unresolved finding prevents a general production/reliability approval, especially for 16 KB devices. Keep the PR unmerged and retain the failure artifacts for follow-up and physical ARM-device validation.



## QA artifacts

Sizes below are APK bytes, not the compressed artifact ZIP size. Each linked artifact contains its named APK; extract the ZIP on the phone before installing.

| APK | Bytes | MiB | Artifact |
|---|---:|---:|---|
| `optimized-arm64-v8a-debug.apk` | 35,951,330 | 34.285860 | [Download](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38952318453/artifacts/12068974253) |
| `optimized-arm64-v8a-release-QA.apk` | 19,530,721 | 18.625947 | [Download](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38952318453/artifacts/12068879519) |
| `optimized-armeabi-v7a-debug.apk` | 34,307,148 | 32.717846 | [Download](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38952318453/artifacts/12068974253) |
| `optimized-armeabi-v7a-release-QA.apk` | 18,186,499 | 17.343997 | [Download](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38952318453/artifacts/12069029355) |

```text
73ad613e18c6d2ec096c0818344558bcb88dbbb02e909f82561e24f0063c159e  optimized-arm64-v8a-debug.apk
bc65224c0ac916eb2e82182acaebe8f1928458d689510041bfbecc56e09479dd  optimized-arm64-v8a-release-QA.apk
5916fe841f4f20f3a29e76e7a92827a871cf6ba566f31eaf5c347a402656e296  optimized-armeabi-v7a-debug.apk
cc6da726e8c72c78a2fe7a1bdd6c630e62956ad007f7917de843b300a89c0838  optimized-armeabi-v7a-release-QA.apk
```

The ARM64 release-QA APK is 26,761 bytes larger than the previous verified 19,503,960-byte gallery QA build (+0.1372%), incorporating the diagnostics and narrow test-entry keep rules without new runtime dependencies. This is separate from the original size-optimization comparison; no additional model/feature removal was performed.

[Verification reports](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38952318453/artifacts/12069014310) include JUnit results, lint, APK inspection and measured size/model-hash reports. Native reports are artifacts on the same run.

Release-QA uses application ID `com.aimediasorter.app`; debug uses `com.aimediasorter.app.debug`. They have separate local state and do not share encrypted keys or URI grants. Existing-installation signing compatibility is unverified; no uninstall or deletion of existing app state is part of this repair.


QA packages use debug signing, including the minified release-QA package. They are testing artifacts, not a production signing/physical-device approval. In-place signing compatibility with an existing installation has not been established. No uninstall, key export or credential migration was performed.

## Remaining evidence gaps

- **HIGH, open:** the independent API 35 / 16 KB / R8 run and targeted retry aborted in ART/DataStore/settings field resolution despite the primary run passing. This remains an unresolved reliability issue, not a silently ignored test failure.

- The precise historical outcome/HTTP count for the user's original 36 files remains unknown because the old build did not retain it. New diagnostics must not be backfilled with invented zero counts.
- The actual user photographs and ten reference images were not available to this test process. The 36-input regression uses variants of one public photograph with distinct EXIF fixture metadata and mocked answers; it tests processing/accounting/safety, not accuracy or the user's dataset.
- No live real-photo classification, quota/billing experiment or physical-device test was performed. Synthetic model success on Gemini 3.5 Flash/Flash-Lite is the user's reported evidence, not a new independent live test.
- Provider-redacted analysis bytes cannot establish original-byte transfer safety. Such media remains blocked for physical organization; no relaxation is included.
- Model response quality, latency and real quotas cannot be inferred from mocked passing tests. Explicit errors now distinguish timeouts, truncated output, invalid JSON and unsupported category values without changing models or semantic rules.
