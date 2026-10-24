# Optional Gemini BYOK engine

Status: implementation under review. Automated tests use mocks and isolated synthetic media. No live Google API request or comparative accuracy evaluation has been completed by the reviewer.

## Operation and consent

Offline is the default. The existing bundled ML Kit model and ONNX diagnostic stay available without a key or a model download. Gemini API and Hybrid are optional settings. Hybrid runs real ML Kit inference first and compares the category with Gemini's answer.

Save your own Google API key in Settings, explicitly fetch the accessible model catalog, and choose a model supporting generateContent. The catalog does not advertise a reliable image-input flag: a selected model must also accept the consented image request; an incompatible model produces review status, never fabricated predictions. No fixed model ID is assumed to be available to every account.

A cloud analysis requires a new unchecked consent dialog for each batch. The batch binds the source folder, enabled category records, analysis settings and key revision. Grants expire after one hour and are revoked when cloud settings/key change or analysis is cancelled. Every upload rechecks the current configuration. The user-selected source can contain many files, but hard per-batch and daily attempt caps limit uploads. The app never schedules automatic library uploads.

Only re-encoded RGB JPEG images (long edge at most 512 pixels) are sent. EXIF/GPS are excluded. Video analysis sends up to three distinct decoded frames requested at 10%, 50% and 90% of duration in one request. It is sampled-frame classification, not full video/audio understanding. Original bytes are read for hashing and preserved; the generated upload JPEGs are not transferred as original files.

## Credential and transport security

The key is encrypted with an Android Keystore AES-GCM key in noBackupFilesDir, outside Settings, WorkManager input, Room records, exports and diagnostics. The password field is not saved across process recreation and applies FLAG_SECURE while nonempty. No API key is committed or bundled. The HTTPS request uses x-goog-api-key, not a URL parameter. Redirects and cleartext traffic are disabled; no HTTP body/header logging is installed. Raw provider errors are discarded in favor of safe messages.

This is a personal BYOK client. A rooted device, instrumentation, malicious keyboard, compromised OS or process-memory inspection can expose a decrypted user-controlled credential. Keystore encryption is not equivalent to holding a server credential on a trusted backend. Restrict the key to the intended API and use Google project quotas/billing controls. Removing a key from the phone does not revoke it at Google.

The cloud-capable APK declares INTERNET even in Offline mode. Application code makes no Gemini request in Offline mode; fetching the model catalog is a separate explicit network action and sends no media. Bundled third-party SDKs may have their own documented metrics behavior when networking is available. This APK must not be described as having no networking permission or as independently proven telemetry-free. Google's API terms and data treatment depend on the account/service tier; review the official terms before uploading sensitive content.

## Limits, retries and accounting

Configure per-batch attempts, daily attempts (UTC), output tokens, retry count and a conservative estimated USD amount per attempt/batch. Zero price estimates keep cloud analysis disabled. Every attempt, including retries, is reserved transactionally in SQLite before the network call. Failed/cancelled requests still consume a local reservation because they may have reached the provider. Reported prompt/output/thinking token counts are recorded when returned successfully.

The local money limit is an estimate based on a user-supplied amount, not a price lookup, invoice, provider hard spending cap or guarantee of actual cost. Token prices, image tokenization, thoughts, provider retries and model behavior vary. Use provider project billing controls for financial enforcement. The hard local request/output caps operate independently of the estimate.

Retries apply only to explicit 429/500/502/503/504 responses, are bounded (0–2), and honor bounded Retry-After/exponential backoff. An uncertain network outcome is not automatically retried. Cancellation disconnects in-flight I/O but cannot undo a request already received or guarantee it is unbilled.

Validated results are cached for seven days against original SHA-256, selected model, category definitions, key revision, frame/preprocessing protocol and output configuration. Per-batch attempt tombstones survive process death and prevent automatically repeating an uncertain request in the same batch. A new expressly authorized batch may retry it. Cache size is limited to 10,000 entries. Mutable provider model versions can change during a cache lifetime.

## Response and transfer policy

The structured response contains only category ID, short_description, alternative_categories and review_status. Unknown IDs, unexpected fields, invalid alternatives, blocked or truncated outputs fail closed. The model supplies no calibrated probability; the existing numeric score slot is unavailable (0) for cloud results and the UI labels that limitation.

Gemini-only results always need individual category confirmation before in-app transfer, even if the provider says CLEAR or a batch includes review items. Hybrid disagreement or API failure requires individual review. Hybrid agreement preserves the actual offline evidence score and the existing transfer approval requirements. File transfer still rechecks original SHA-256, verifies destination readback before any deletion, and reports filesystem timestamps separately from EXIF/MediaStore fields. Exported scripts preserve existing explicit operator approval gates; their --include-review option remains an explicit review override, not a Gemini permission.

## Verification and optional live tests

Normal CI never receives a real API key and never uploads media to Google. Mock JVM tests cover schema validation, accessible models, retries, errors, budget callbacks and cancellation. Android tests cover real Keystore encryption, durable SQLite limits/cache/revocation, bounded image/video sampling and the launcher icon; existing SAF, original-byte, Room, ONNX, ML Kit and transfer tests remain enabled for Android 14 and Android 15, including 16 KB pages and a minified release.

The optional instrumentation method optionalLiveGeminiProtocolRequiresExplicitOperatorConfiguration is skipped unless both allowCloudUpload=true and liveGeminiModel=models/gemini-... runner arguments are supplied. First configure the key on the isolated test device through app settings; never pass it on a command line. The live test uploads one synthetic image with a one-attempt cap. It is a protocol smoke test, not a real-photo accuracy benchmark.

The requested 36 representative labeled photos have not been supplied. No accuracy improvement is claimed. Run Offline and Gemini separately on the exact same isolated 36-file collection, export JSON reports, and compare with scripts/compare_classifiers.py using a human-labeled SHA-256 manifest. The comparison refuses missing, extra, duplicate or mismatched media.

Official references:
- https://ai.google.dev/api/generate-content
- https://ai.google.dev/api/models
- https://ai.google.dev/gemini-api/docs/structured-output
- https://ai.google.dev/gemini-api/docs/api-key
- https://ai.google.dev/gemini-api/docs/billing
- https://ai.google.dev/gemini-api/terms
