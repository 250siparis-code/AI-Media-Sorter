# Privacy-safe diagnostics

Implementation branch: `codex/privacy-safe-diagnostics`, stacked on `codex/real-photo-pipeline-repair`. This change does not merge either branch into main.

## User workflow

Settings → Diagnostics offers **Export Last Analysis Report** and **Export All Diagnostic Logs**, in UTF-8 JSON or TXT. The app shows the exact snapshot's byte size and event count before opening Android SAF CreateDocument. New events arriving after that preview are excluded from that export. Nothing is uploaded automatically. Clearing diagnostic logs requires confirmation and does not clear media, classifications, credentials, cache, transfer journals or operation approvals.

Results/virtual galleries show the failure count and a direct last-analysis export button. The analysis completion screen also has an export button. A diagnostic report can be shared without another API batch.

## Data contract and privacy

Events carry a schema-controlled operation kind, stage/status, random correlation/parent/batch UUIDs, UTC epoch timestamp, elapsed milliseconds since operation start, validated public Gemini model identifier, HTTP status, finite error/validation codes and allowlisted numeric metrics. Each media operation and each HTTP attempt has its own correlation ID; parent IDs connect requests to the media operation and batch.

There is deliberately no free-text logging or exception formatter. Keys, authorization headers, personal filenames, filesystem paths, URIs, media fingerprints/hashes, category descriptions, image/video bytes, model descriptions/reasons and raw provider bodies never enter the logging API. Ingestion, persistent reads and export enforce the allowlist again. Invalid identifiers/model strings are omitted; unknown error text becomes UNRECORDED; unknown stored fields are ignored. Application version/build number, Android release/API and hardware model are included with validated technical values. Android device names, serials, account names and advertising identifiers are not collected.

JSON schema version 2 contains a chronological event list, operation indexes grouping correlations, batch summaries and anonymized per-file summaries pointing into that list. TXT preserves chronological ordering with the same correlation fields. Validation details are finite tags such as JSON_SYNTAX, REQUIRED_FIELDS and CATEGORY_VALUES; exception messages or provider excerpts are never used to explain a validation failure.

## Request accounting and stage interpretation

- REQUEST_NOT_SENT means no **complete POST body** was acknowledged by the local transport. Network/timeouts can still involve partial upload; reports use PARTIAL_OR_UNKNOWN for those outcomes, never imply that zero bytes left the phone.
- REQUEST_SENT is emitted at completed POST body write/close. It proves neither provider receipt nor billing. A successful catalog GET is acknowledged after receiving its HTTP response.
- Reservations/pacing are separate from sent requests. Configured output-token, batch/daily-request, retry and pacing limits, plus successfully parsed provider token-usage counters, are recorded as numeric technical metadata. REQUEST_BUILD, local preprocessing, quota reservation, retry wait, HTTP headers/failures, response receipt, JSON parsing, schema validation, classification success and manual review have separate events.
- Per-file request counts are the maximum cumulative media-operation counter, not a sum of REQUEST_SENT events across parent and child records. Batch summaries use the existing persistent sent-request counter.
- Cache reuse, pending consent/budget, local decode failure and uncertain network outcomes remain distinguishable. No result is guessed and no offline fallback is introduced.
- Durations are elapsed monotonic milliseconds relative to that correlation's start; subtract matching stage start/end values to obtain stage duration. HTTP child operations have independent clocks.

## Retention and failure isolation

The app-private SQLite diagnostic database has a default maximum of **10,000,000 bytes**, bounded records (up to 4,000 events and 20 batch summaries), a 4,096-byte event ceiling and seven-day expiry. An actual UTF-8 byte counter and payload budget leave room for SQLite/index overhead; rotation may occur before the advertised upper event count. WAL is not enabled. Short-lived SQLite rollback journals and an explicitly exported user document are not retained diagnostic history. Clearing compacts the database.

A bounded 512-command channel feeds one IO writer. Normal logging uses trySend and never waits for disk/network; queue overflow is counted and reported. Storage exceptions update a local health counter rather than propagating into analysis or file operations. Export/clear are explicit asynchronous operations. A storage failure can prevent exporting unavailable data, but does not authorize a media change.

A small AtomicFile crash marker stores only timestamp, random ID and a finite exception-type number, delegates to the existing crash handler and is ingested/deleted on restart. Retained unfinished batch/worker/copy/move correlations are marked PROCESS_RECOVERY on restart. Android ApplicationExitInfo also contributes only finite own-process crash/ANR reason/status and RSS numbers when available; descriptions and trace streams are never read. Native crashes, abrupt OS kills, power loss and events dropped before persistence can leave incomplete histories; no stack trace or raw exception is captured. Recovery diagnostics never resume source deletion.

## Safety and compatibility

API 34 minimum and compile/target 35 remain unchanged. Existing ARM64/ARMv7 builds, icon, R8/resource shrinking and the five native test configurations remain enabled. The request builder, selected model, prompts, classification rules, offline policy, original-byte access, SHA-256 transfer engine and timestamp/delete algorithms are preserved. Repository wrappers observe existing checkpoints; they do not change approval requirements or storage destinations. Diagnostic export has a separate schema and never serializes the older personal operation-report entities.

## Verification and limits

Automated coverage includes both export modes/formats, chronological grouping, correlation uniqueness, secret/path/filename/raw-body rejection, typed JSON/schema errors with mocked Gemini responses, logger failure isolation, persistent rotation/clear, database byte bounds, crash markers, real JPEG → mocked HTTP → Room → anonymized report, SAF export byte-count equality and unchanged isolated source content, plus installed Settings/Diagnostics preview and clear confirmation UI.

No real Gemini batch or physical-device test is performed by this change. Events from before this diagnostic system was installed, or from rotated/cleared history, cannot be reconstructed. Reports explicitly mark history as potentially truncated. This is a QA build, not a production signing or physical-device reliability claim.

The prior independent API 35 / 16 KB / R8 test instability (DataStore/ART failures documented in REAL_PHOTO_PIPELINE_REPAIR.md) remains an open risk until separately verified/root-caused; it must not be hidden by rerunning to green or disabling tests.

Final executed test counts, workflow links and APK measurements will be appended after CI completes.

### Local verification at 693f8e16c2c130e0290be1c30585b67a00bebc74

- JDK 17, Gradle 8.9, Android SDK 35: `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` succeeded. JVM: 56 tests, zero failures/errors/skips. Python: 20 tests passed.
- The generated ARM64 debug APK inspection verified minSdk 34, targetSdk 35, APK signature and native alignment. No local emulator is available (`/dev/kvm` absent); instrumentation execution is delegated to the unchanged GitHub Actions matrix.
- Environment-only setup failures were resolved without changing project configuration: the preinstalled Java runtime lacked jlink; the downloaded JDK needed the system proxy trust store. A subsequent local release attempt exhausted the container's memory while old Java compiler daemons remained resident; stale daemons were stopped and the lower-memory release verification was restarted.
- A second application-side IO-independent slot preserves the latest 20 pending batch summaries under ordinary-event queue pressure; the final report summary is not silently dropped with normal events. Overflow itself remains visible as a counter/event.

Release R8 mapping inspection showed the new Application diagnostics getter was inlined into MainViewModel. The instrumentation fixture now accesses the existing kept Diagnostics API instead of depending on that removable getter; no broad production keep rule was added. Diagnostic SQLite schema 2 adds an actual UTF-8 byte counter with a non-destructive version-1 migration, improving retention of small events while retaining the hard 10 MB file cap.

### Historical results without another upload

Last Analysis Report can also read the most recent saved Room session when that session predates recorded diagnostics. It exports only allowlisted saved counters/model IDs and anonymous per-file summaries; it never reads media bytes or calls Gemini. Rows without a fresh analysis timestamp in that session are omitted rather than falsely assigning unselected/cached media to the batch. Missing stages, HTTP status, request counts and durations remain unknown/null, with LEGACY_SUMMARY and HISTORICAL_DETAILS_UNAVAILABLE markers. Saved free-text notes, provider text, filenames and source paths are excluded. The environment describes the exporting build; the historical analysis build is unknown. Clear records a persistent cutoff so cleared history is not silently reconstructed.

### Initial native findings and corrections

At 7876205, the five emulator configurations completed but failed new diagnostic tests (debug: two assertions; release: those fixture checks plus shared-serialization ABI calls). The real JPEG fixture used an unquoted primitive which kotlinx.serialization accepted before object-schema rejection; it now supplies a genuinely truncated JSON object to exercise JSON_PARSE. Two hundred small events did not exceed the configured byte budget (76,600 bytes versus 78,643); the rotation fixture now submits 400 and prints safe size/count evidence on failure. Release instrumentation had inlined Json extension calls into its separate APK, referring to StringFormat/getSerializersModule APIs that R8 correctly optimized in the application. Tests now use kept, typed application encoding/decoding facades; serialization shrinking remains enabled and no broad SDK keep rule was added. No tests were disabled, and these failed runs are not described as verified QA builds.

### Verification at 6caf410 and remaining 16 KB risk

Push workflow [38971823471](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38971823471) succeeded: 57 JVM tests, 20 Python tests, lint, four ABI/build-type APKs and all five native configurations. Each native configuration executed 67 tests: 66 passed, one optional live-upload test skipped, zero failures/errors.

The independent PR workflow [38971830056](https://github.com/250siparis-code/AI-Media-Sorter/actions/runs/38971830056) passed the build and four native configurations but crashed in API 35 / 16 KB **debug**. That process ended after 42 reported tests with SIGSEGV in libart.so NterpGetStaticField+294 while resolving AnalysisCode.Companion.forKind through GeminiClient.checkHttp. The image was google/sdk_gphone16k_x86_64/emu64xa16k:15/AE3A.240806.043/12960925. This extends the previously observed ART instability beyond R8; its root cause is unverified and remains HIGH. It is not dismissed as environment-only, no test was disabled, and no targeted rerun was used to conceal it.

The final follow-up changes only diagnostic report preparation: event ancestry grouping is linear in the event count (bounded parent depth), cancelling/clearing prevents a stale preview from being published, and recovered durations are unknown across process loss. Local verification after that follow-up: 58 JVM tests plus lint/debug/instrumentation compilation succeeded. Final CI results follow below.

QA builds use debug signing, not production signing. Upgrade-signature compatibility with an existing installation is unverified; do not uninstall the existing app to obtain this report, because that would remove its private saved results and key/grants. Historical export depends on retaining the original app data through a compatible update. Physical-device validation and live Gemini accuracy evaluation remain unperformed.
