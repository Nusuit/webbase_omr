# Android Release -> Wasm Migration Plan

This document is based on the current Android release artifacts and source in `android/`.

## What exists today

- Release APK exists at `android/app/build/outputs/apk/release/app-release.apk`.
- Android app uses `org.opencv:opencv:4.9.0` via Maven (OpenCV Java wrapper).
- App code is Kotlin-centric under `android/app/src/main/java/com/gradesnap/omr`.
- No custom `app/src/main/cpp` JNI OMR core is present in this repo snapshot.

## Migration goal

Use one shared C++ OMR core for:
- Android (JNI wrapper)
- Web (Emscripten Wasm wrapper)

## Immediate implementation done in this branch

- Added C++ core module skeleton in `src/core`.
- Added C ABI bindings (`omr_process_frame`, `omr_process_frame_v2`).
- Updated `CMakeLists.txt` to build modular core and export new symbols.
- Refactored worker protocol to stable namespaced messages.

## Next implementation steps (priority order)

1. Port normalization stage
- Source of truth: `NormalizePaper.kt`.
- New target: `src/core/normalize.h/.cpp`.
- Add unit image fixtures for marker-corner ordering and warp output size.

2. Port bubble detector stage
- Source of truth: `BubbleDetector.kt`.
- New target: `src/core/bubble_detector.h/.cpp`.
- Keep threshold constants parity (`FILL_THRESHOLD`, `MIN_GAP`, suspicious logic).

3. Port orchestration stage
- Source of truth: `OmrProcessor.kt`.
- New target: `src/core/pipeline.h/.cpp`.
- Output struct should include `mssv`, `exam_code`, `answers`, `suspicious`, `raw_ratios`.

4. Add config parser
- Source file: `android/app/src/main/assets/omr_layout_config.json`.
- New target: `src/core/layout_config.h/.cpp`.
- Parse once in worker init; pass typed config to core pipeline.

5. Stabilize worker API
- Keep protocol version in `web/js/worker-protocol.js`.
- Add new message types:
  - `omr/process-image`
  - `omr/process-batch`
  - `omr/progress`
  - `omr/result-json`

6. Regression gate
- Build golden test set from current Android release output.
- Compare per-question parity between Android and Wasm outputs.
- Ship only after parity threshold is accepted.

## Suggested folder target (next commit)

```text
wasm-omr-mobile/
  src/core/
    omr_core.*
    omr_bindings.*
    normalize.*
    bubble_detector.*
    layout_config.*
    pipeline.*
  web/js/
    worker-protocol.js
    worker.js
    wasm-bridge.js
```
