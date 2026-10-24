# Main recovery and independent application verification

Date: 2026-10-24 UTC. Base: main `7a0a0b082073b568d1671b30b37584ba3ecf71d9`.
Branch: `codex/main-recovery-and-verification`. Main is not automatically merged or overwritten.

## Confirmed blockers repaired

Main contained 51 unresolved merge-marker lines in eight tracked files. Its Actions run 40120751191 failed without executing jobs; independent YAML/XML parsing failed, and a local Gradle attempt stopped at `processDebugMainManifest` before JVM tests.

The workflow, manifest, README, storage scanner, report/Termux exporters, SafeFileMover and acceptance tests are restored to the reviewed `91ab2dff` implementations, preserving later valid documentation. This removes the non-compilable legacy alternatives, including pre-verification atomic MOVE, byte-count-as-hash fallback, force overwrite and incorrect filename quoting. `SafeFileMover` again delegates to `VerifiedTransferEngine`; explicit approvals, original SHA-256 readback, publication/source checks, checkpoints and conservative timestamp/source-retention behavior remain intact. Gemini v3, galleries, manual correction, app icon, private diagnostics, API34 minimum, compile/target35 and offline diagnostic model assets are retained.

`scripts/check_source_integrity.py` rejects merge markers and invalid app/src XML before builds, including extracted source archives. Tests cover detection, malformed XML, source confinement and credential/cache exclusion. CI uses the canonical current repository for historical size-baseline retrieval rather than the old owner name.

## Safe QA installation and signing

QA release builds have distinct package `com.aimediasorter.app.qa`, label **AI Media Sorter QA**, and `-qa` version suffix. Production package/signing is unchanged; debug remains `.debug`. The official icon is retained. FileProvider authority follows the actual package. Existing app data/key/grants are neither migrated nor erased by installing the separate QA app. QA requires its own secure key entry, model selection and source/destination grants.

Release emulator jobs install an independent debug fixture app, seed a fixed private sentinel, run the full release suite against QA, then verify different UIDs and retention of the debug app's private sentinel. This is restricted to temporary emulators and is not a physical-device upgrade/data-migration test.

Optional `QA_DEBUG_KEYSTORE_BASE64` is used only on trusted push/manual CI events; PR runs deliberately receive no signing secret. `configure_qa_signing.py` validates a private androiddebugkey entry using JDK keytool, restricts runner files to700/600, exports only the temporary path and public certificate fingerprint, and fails closed on invalid supplied input. No private key is committed, cached or uploaded. Without a configured secret, mode is explicitly `ephemeral_runner_debug`; installed APK inspection records the actual certificate SHA-256 and package/version.

GitHub integration could not read Actions-secret metadata (HTTP403). A stable QA key is therefore not claimed configured. Original-package in-place upgrades still require its original signing key; no code change can recover that key or guarantee compatibility with the user's installation. Co-installation fixes the immediate need to uninstall the old app, but not historical private-state migration or production signing.

## Runtime stability investigation

All existing API34/35 profiles remain required. API36/16KB debug and release comparisons are added using the officially available `google_apis_ps16k` image; compile/targetSdk stay35. The runner records bounded, sanitized runtime fingerprints, page sizes, ART/APEX/emulator/VM metadata and allowlisted crash details before teardown without changing VM behavior or masking the original test failure.

The earlier crashes span debug and release (ReferenceQueue, ART field/method resolution and Compose JIT). Existing evidence does not justify an R8-only fix, a speculative dependency change, or dismissal as environment-only. See [exact evidence and diagnostic contract](RUNTIME_16KB_INVESTIGATION.md). A passing new matrix does not retrospectively establish the root cause; that historical issue remains HIGH/unverified until isolated or supported by a demonstrated platform remedy.

## Functional scope and evidence limits

Source review found no additional proven Gemini/settings/gallery blocker requiring a rewrite. Existing native tests exercise AQ key persistence/settings, model catalog/protocol mocks, actual JPEG and video decoding, per-file errors and quotas, Room virtual categories/review, manual correction, explicit plan approval and real verified transfers in isolated temporary directories. Context-only strings cannot add destination categories; multiple distinct Gemini category proposals require manual review.

No live Gemini batch or actual user media is used here. The 17 mock composition cases verify the application contract, not model visual accuracy. Real subject-intent correctness, new prompt live-provider compatibility, representative video accuracy, OEM original-byte/timestamp behavior and physical ARM64 acceptance remain separate evidence gates. The local comparison tool is available, but no before/after accuracy percentage is fabricated.

## Executed verification

Actual local/CI test results, final source identifiers and downloadable QA APK artifacts will be appended after execution. A compilation success alone is not acceptance of semantic accuracy or destructive operations on a real archive.
