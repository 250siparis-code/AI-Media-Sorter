# AI Media Sorter — Native Android Application (`com.aimediasorter.app`)

Requires **Android 15 (API 35) or later**.

Native Android application built with **Kotlin**, **Jetpack Compose**, **Material 3 (Pure Black AMOLED `#000000` / `#8B45FF`)**, **Room**, **WorkManager**, **ONNX Runtime Mobile**, **ML Kit Image Labeling**, and a standalone **Termux-Compatible File Operation Export** engine.

---

## 1. Important Disclosure on AI Models & Score Calibration (For Code Audit)

1. **Bundled 10 KB ONNX Model (`app/src/main/assets/models/media_visual_embedder.onnx`)**:
   - This 10 KB ONNX Opset 13 graph is a **heuristic / demonstration projection model** that operates on a 64-dimensional hand-crafted spatial, color-histogram, and Sobel-edge descriptor extracted by `VisualFeatureExtractor.kt`.
   - **Limitation**: It is included to validate the end-to-end offline `ai.onnxruntime` execution pipeline, tensor plumbing, and primary-subject ROI weighting without requiring an initial multi-megabyte download. **Its outputs must not be treated as validated general-purpose semantic image recognition.**
   - **General-Purpose Semantic Recognition**: For real-world semantic image recognition, the application integrates:
     1. **Google ML Kit On-Device Image Labeling (`com.google.mlkit:image-labeling:17.0.8`)** in `HYBRID_ONNX_AND_MLKIT` mode (offline 400+ class MobileNet visual classifier).
     2. **External MobileCLIP / Vision ONNX Model Import (`CUSTOM_MOBILECLIP_ONNX`)**: `OnDeviceAiClassifier.kt` and `VisualFeatureExtractor.bitmapToNormalizedNchwBuffer()` support `[1, 3, 224, 224]` NCHW CLIP-normalized RGB tensors so a full MobileCLIP-S0 `.onnx` vision backbone can be imported from device storage.
2. **Uncalibrated Softmax & Similarity Scores**:
   - The temperature-scaled softmax outputs (`calibratedConfidence` / `classificationScoreUncalibrated`) and embedding dot-products (`rawSimilarityScore`) produced by `PrimarySubjectDisambiguator.kt` are **uncalibrated heuristic ranking scores**, not empirically calibrated probabilities (no Platt scaling or isotonic regression on a held-out validation dataset has been applied).

---

## 2. Termux-Compatible File Operation Export (`TermuxOperationExporter.kt`)

Located in `app/src/main/java/com/aimediasorter/app/domain/storage/TermuxOperationExporter.kt` and exposed in the **Export Operations** panel of `AnalysisResultsScreen.kt`.

- **Works 100% Independently of In-App File Moving**: Generating an export never moves, modifies, or deletes any original media files.
- **Four Output Formats**:
  1. `classification_report.csv` — Human-readable classification report with source path/URI, resolved POSIX path, SAF restriction flag, destination directory, filename, detected/effective category, uncalibrated score, media type, original modification timestamp, and requested operation.
  2. `file_operations.json` — Machine-readable JSON manifest containing all metadata and schema version.
  3. `move_files.sh` — Executable POSIX/Termux shell script for moving files (`MOVE`).
  4. `copy_files.sh` — Executable POSIX/Termux shell script for copying files (`COPY`).
- **Termux Script Safety Guarantees**:
  - **Defaults to Dry-Run Mode (`DRY_RUN=1`)**: Running `sh move_files.sh` prints planned actions without modifying any files.
  - **Requires Explicit Confirmation**: Passing `--execute` prompts the user to type `YES` before any file operation begins (or `--execute --yes` for automated batch runs).
  - **Strict POSIX Single-Quote Escaping**: Preserves Unicode characters (`ç, ğ, ı, ö, ş, ü`, emoji, CJK), spaces, quotes, and special shell characters safely (`'...'` with `'''`).
  - **SHA-256 Verification Before Deletion**: Copies to a temporary `.aimediasorter.tmp.$$` file first, verifies `sha256sum` equality between source and destination, restores the original modification timestamp via `touch -m -d "@<epoch>"`, verifies `stat -c %Y`, and deletes the source file in `MOVE` mode **only** after verification succeeds.
  - **SAF (`content://`) Detection**: Explicitly detects and reports files that only have an unresolvable Android `content://` URI (`[SAF-ONLY]`) instead of treating them as ordinary filesystem paths.

---

## 3. GitHub Actions CI & Building the Debug APK

This repository includes `.github/workflows/android-build.yml`, which automatically:
1. Configures **JDK 17 (Temurin)** and **Android SDK Platform 35**.
2. Runs unit tests (`gradle testDebugUnitTest`).
3. Compiles the Debug APK (`gradle assembleDebug`).
4. Uploads `app-debug.apk` as a downloadable workflow artifact (`AIMediaSorter-debug-apk`).

> **Note**: No Android APK is claimed to have been compiled in the offline Linux generation environment; the APK is built when the GitHub Actions workflow runs (or locally via `./gradlew assembleDebug`).
