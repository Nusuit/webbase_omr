# Native Android OMR — source snapshot

Snapshot of the native OMR pipeline (`com.gradesnap.omr`) from the sibling
Android project `../android` (which is built with
`gradlew.bat -p ../android assembleAgentDebug`). Kept here for versioning
because that project is not itself a git repo.

Key files for the corner-keypoint native path (web parity, 99.44%):
- `CornerKeypointDetector.kt` — corner_detect.onnx (960, single-class) → 4
  corners → homography (mirrors web omr_warp.cpp NormalizeSheetWithCorners).
- `CornerBenchmarkRunner.kt` — tri-provider full-pipeline benchmark.
- `HeadlessBenchmark.kt` — headless N-sheet runner (method cv|yolo|corner).
- `NormalizePaper.kt` / `BubbleDetector.kt` / `OmrProcessor.kt` — CV core.

The ONNX corner model is `web/models/corner_detect.onnx` (same file deployed
to `../android/app/src/main/assets/corner_detect.onnx`).
