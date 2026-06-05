# Android Native Runtime

The `android/` folder inside this `wasm-omr-mobile` repo **does not** contain the
Android project. The complete buildable Native project is the sibling folder of
this repo:

```
C:/Kien/Mobile/orm/android        (relative: ../../android from this file)
```

## Current State (updated 2026-05-30)

The project at `../../android` is **complete and buildable**:

- `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`, `gradlew.bat`,
  keystore (`gradesnap-release.jks`).
- 31 Kotlin files in `app/src/main/java/com/gradesnap/omr/`, including
  `HeadlessBenchmark.kt`, `CvBenchmarkRunner.kt`, `YoloBenchmarkRunner.kt`,
  `OmrProcessor.kt`, `PerformanceTelemetry.kt`, `FreqMonitor.kt`.

Correction: earlier notes saying "workspace lacks Android source/build, cannot
rebuild" were **wrong** — they only looked at the empty `wasm-omr-mobile/android/`
folder, not the sibling `orm/android/` project.

## Native per-question export — not blocked

A headless N=179 runner already exists (`HeadlessBenchmark`, triggered via
`BenchmarkActivity` with `--es headless true --es method cv|yolo --es manifest
<path>`). It writes per-sheet JSON matching the Web schema (`answered`,
`suspicious`, `multi`, `examCode`, `mssv`, `cpp_ms`, `e2e_ms`, `ort_ms`, etc.).

Per-question data is already computed in `OmrProcessor` (`result.answers`) and is
used to derive the exported `answered`/`multi` counts in both `CvBenchmarkRunner`
and `YoloBenchmarkRunner`. It is simply not serialized per-question yet.

To make Native a recognition baseline, add `auto_q01..auto_q60` (from
`result.answers`) to `HeadlessBenchmark.writeJson` and the matching `SheetCapture`
fields, then build:

```powershell
cd ../../android
.\gradlew.bat assembleTraditionalDebug assembleYoloDebug
```

## Paper Boundary

Until per-question export is added and rerun, Native Android stays scoped as a
latency/resource baseline in the manuscript, not yet a supervised recognition
baseline. Existing Native JSONs in `runs/batch_results/native_results_*_n179.json`
remain valid for the latency/resource boundary but must not be mixed with Web
recognition artifacts as if they shared the same export schema.
