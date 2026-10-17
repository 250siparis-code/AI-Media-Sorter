# Gemini photographic subject and intent repair

Branch: `codex/gemini-subject-intent`, based on `codex/privacy-safe-diagnostics` at `fd404c137d334e96da7eef38bdd130f7f608e173`. This is a separate, stacked change; no branch is merged automatically.

## Problem and limits of the diagnosis

The user reported that an obvious portrait was assigned Food because of nearby food, and a pool/landscaped scene was assigned Buildings because a house was visible. These are user-reported real-device outcomes. The actual photographs, corresponding provider responses and labeled comparison collection are not available to this test process. The historical cause of each model choice cannot be proved from those descriptions alone.

The inspected v2 prompt briefly asked for “independently important main subject” categories. Its wire schema returned a flat category list and a separate subject list, with no category-to-subject association. It did not require the model to identify the primary photographic subject, justify the independent significance of an additional subject, or place contextual objects in a separate field. Those contract weaknesses are repaired here. This is a prompt/schema intervention, **not yet evidence of improved real-image accuracy**.

Inspection also found `CategoryDao.getAllCategories()` orders rows by `priority DESC, name ASC`. The v2 request sorted category records by ID but copied the incoming order into the schema's category enum. The revised request canonicalizes both subject-category enums by ID, so changing priority or incoming order cannot change the request. This removes an unintended ordering influence; it does not prove that enum order caused the reported model choices.

## Actual request and response changes

`CloudProtocol.request` now asks Gemini to classify photographic subject/intent using framing, composition, focus, prominence, relative visual importance and interaction. Pixel area, mere visibility and recognizable nouns alone are insufficient. It asks for a short evidence-based decision summary, not invented certainty or an imagined backstory. The same composition test applies to every configured category; there is no fixed People/Food/Nature/Buildings precedence, filename exception or photo-specific rule.

The required wire fields are:

| Field | Contract |
|---|---|
| `primary_subject` | Short description of the deliberately emphasized subject; may be empty when unusable. |
| `primary_category` | Configured category ID, or empty string to abstain. |
| `independently_significant_secondary_subjects` | Up to nine objects containing `description`, configured `category`, and a nonblank `significance_reason` tied to composition. |
| `contextual_objects` | Up to twelve descriptive strings; never used to create a category link. |
| `pose`, `scene`, `details` | Descriptive metadata; never used to create a category link. |
| `category_decision_reason` | Short explanation of the subject/category decision and incidental context. |
| `review_required` | JSON boolean; the application can conservatively require review even if this is false. |

The category proposal is exactly the unique union of the validated primary and significant-secondary category IDs, preserving the primary's display order. Context is not scanned for category keywords. Every category in that union is offered in the existing galleries without an automatic winning category. Two or more distinct categories, zero categories, or an explicit ambiguity flag deterministically require manual review. Several meaningful subjects within the same category can remain one category, such as a group portrait. A tie can retain a provisional primary and all significant alternatives for review; it does not authorize a physical operation.

Unsupported IDs, missing/extra fields, invalid types, absent primary descriptions for nonempty primary categories, orphan secondary subjects, blank significance reasons, overlong strings, control characters and oversized arrays fail closed through the existing sanitized diagnostics. There are no generated numerical confidence values. Provider safety/token/JSON/HTTP errors remain separate from semantic review; an invalid response never triggers offline fallback or a guessed category.

The Gemini model selection, official endpoint, JPEG encoding/size, quota reservations, retry policy, token budget, opt-in consent and privacy protections are unchanged. Videos still use joint sampled-frame analysis; changing important subjects or incomplete evidence requires review, and a single decoded frame is always forced to review. No original video is transcoded or altered.

## Saved results, cache and UI

The protocol identifier changes from `gemini-independent-subjects-v2;rgb-jpeg512;video-3-distinct-frames;selected-uri-v1` to `gemini-photographic-intent-v3;rgb-jpeg512;video-3-distinct-frames;selected-uri-v1`. Both the cloud cache key and repository configuration fingerprint already include that identifier. Prior automatic predictions are not returned as fresh v3 analysis. This change does not initiate any upload; the existing explicit selection, consent and batch authorization are still required.

`CloudDecision` retains its older saved fields and adds defaulted structured subject/context fields. Existing Room gallery records and legacy cached values still decode; legacy wire responses are rejected by the new parser. No database migration or destructive reset is needed. Manual corrections remain managed by the existing repository workflow; the new prompt does not grant permission to overwrite a correction or move a file.

The existing detail/review screen displays the primary subject, independently significant secondary subjects and their justifications, and a clearly labeled context-only list. Old saved results use their existing subject display. New labels are localized in Turkish and English. Gallery category membership, manual corrections, full-screen viewing and approved Copy/Move workflows retain their existing behavior.

Diagnostic events retain their privacy allowlist. `REQUEST_BUILD` records the numeric `ANALYSIS_PROTOCOL_VERSION=3`; validation adds finite tags for primary/secondary/context fields, without subject text, category prose, image contents, filenames, paths, credentials or provider bodies. Diagnostic export remains local and explicit.

## Tests and accuracy evidence

`SubjectIntentTest` includes 17 human-written composition scenarios with **mocked** Gemini responses: restaurant portrait with incidental food; food close-up with incidental people; pool/garden with a background house; architectural facade with incidental garden/pool; independently featured person/food in both primary orders; portrait with significant cityscape; a small but independently important climber; group/event; pet; vehicle; technology product; text-focused image; landscape with incidental pedestrians; uncertain single subject; unusable scene; and multiple important subjects sharing one category.

These fixtures exercise request construction, strict parsing, metadata preservation, category linkage and deterministic review. The scenario descriptions are not photographs; their expected mock answers are not model predictions. They do not establish recognition accuracy. The existing isolated photographic JPEG suite exercises actual Android preprocessing and persistence with mocked provider replies. No live API batch is run by this change, and no real-device testing is claimed.

`scripts/compare_gemini_subject_intent.py` prepares a reproducible local comparison without reading/uploading media or calling any API. It requires the same anonymized case IDs, human-labeled category sets/review expectations, the same model, explicit v2/v3 versions, uncorrected predictions and declared evidence source. It reports exact category-set, review and joint matches, plus failures, pending and abstentions. Failures/pending remain in the known-category denominator. Unknown-category human truth is scored separately in joint/review matches. Mock sources receive contract counts only, with no accuracy rate. A `real_gemini_api` tag is an operator declaration, not proof that calls occurred.

### Local comparison input

Create three UTF-8 JSON files locally. Keep any case-ID-to-original mapping private and separate. Do not put personal filenames, media hashes, paths, descriptions, keys or raw provider bodies in these files.

Labels:

```json
{"configured_categories":["cat_people","cat_food"],"cases":[{"id":"case-001","categories":["cat_people"],"review_required":false}]}
```

Baseline predictions:

```json
{"evidence_source":"mocked","model":"models/gemini-test","protocol_version":"gemini-independent-subjects-v2;rgb-jpeg512;video-3-distinct-frames;selected-uri-v1","results":[{"id":"case-001","categories":["cat_food"],"review_required":false,"status":"PROPOSED","manually_corrected":false}]}
```

Revised predictions use the same shape and the v3 version above. This illustrative baseline is invented contract data, not an observed model result. Actual evaluation must use genuinely captured baseline/revised predictions and set `evidence_source` accordingly; do not relabel mocks as real.

```bash
python3 scripts/compare_gemini_subject_intent.py labels.json baseline.json revised.json
```

Before any future live comparison: assemble independently labeled, actual consented images containing both directions of each composition pair, ambiguous and multi-category scenes, plus varied framing/backgrounds; keep the selected model, image preprocessing and generation limits identical; preserve all failures; obtain explicit upload/budget authorization; and record prediction provenance. Do not tune on all evaluation images and then report that same set as independent accuracy. Nothing in this PR enables an automatic evaluation upload or requires Android Studio.

## Verification status and remaining risks

Local JDK17/Gradle8.9 execution completed with **68 JVM tests, zero failures/errors/skips**; **28 Python tests passed**. `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` passed, including compilation of native subject-context persistence and installed UI assertions. These counts are contract/safety evidence, not visual accuracy. Native execution is delegated to CI because the local machine has no KVM device. Final CI results follow when available.

Executed build/test results and downloadable workflow artifacts will be recorded here after verification. Real Gemini visual-accuracy improvement remains **unmeasured** until a matched real-image evaluation is actually performed. A prompt can improve the decision contract but cannot guarantee a model will infer photographic intent correctly, especially from reduced-resolution images or ambiguous compositions. Manual review remains necessary.

The pre-existing intermittent API35/16KB ART/ReferenceQueue/Compose runtime crashes remain an unresolved HIGH risk, documented in [PRIVACY_SAFE_DIAGNOSTICS.md](PRIVACY_SAFE_DIAGNOSTICS.md). Alignment checks or one passing emulator run do not close that issue. QA builds use debug signing; compatibility with an existing installation's signing key is unverified. Do not uninstall the existing app to update, since that removes saved results/key/grants. No production-signing or physical-device reliability claim is made.

## Changed files

- `app/build.gradle.kts`: build 6, version 1.1.3-subject-intent; SDK, ABI and shrinking settings unchanged.
- `app/src/main/java/com/aimediasorter/app/domain/cloud/CloudProtocol.kt`: actual prompt, linked structured schema, strict parser, saved-decision compatibility, redaction, protocol/cache version and request diagnostic version.
- `app/src/main/java/com/aimediasorter/app/domain/cloud/CloudAnalysisService.kt`: keep the structured review boolean consistent when a video has only one decoded frame.
- `app/src/main/java/com/aimediasorter/app/domain/diagnostics/DiagnosticEvent.kt`: finite field tags and numeric protocol-version metadata; no free-text logging.
- `app/src/main/java/com/aimediasorter/app/ui/screens/MediaReviewScreen.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values-tr/strings.xml`: display separate subject/context metadata with both locales.
- `app/src/test/java/com/aimediasorter/app/SubjectIntentTest.kt`: varied mocked compositions, schema, limits, ambiguity, priority independence, compatibility, redaction and diagnostic tests.
- `app/src/test/java/com/aimediasorter/app/CloudProtocolTest.kt`, `app/src/test/java/com/aimediasorter/app/DiagnosticExportTest.kt`: migrate response fixtures without weakening HTTP/privacy/safety assertions.
- `app/src/androidTest/java/com/aimediasorter/app/RealPhotoDiagnosticsTest.kt`, `RealPhotoPipelineTest.kt`, `VirtualGalleryNativeTest.kt`, `VirtualGalleryUiTest.kt` in the same directory: new wire fixtures, synthetic-vs-photo schema equivalence, persistent subject/context separation, real thumbnail/details/viewer assertions and unchanged media checks.
- `scripts/compare_gemini_subject_intent.py`, `tests/test_compare_gemini_subject_intent.py`: local comparison contract, evidence labeling and eight synthetic Python regression tests.
- `docs/GEMINI_SUBJECT_INTENT.md`: this independent report and evaluation procedure.
