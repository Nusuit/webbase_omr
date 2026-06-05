# Rerun Plan for OMR Benchmark Hardening

Date: 2026-05-28

Update 2026-05-29: Redmi USB debugging is available and Web CV/YOLO raw reruns
have been completed. See `docs/RERUN_SUMMARY_20260529.md`.

Update 2026-05-30: The "Native export blocked by missing Android project" note
below is **incorrect**. The complete buildable project is the sibling folder
`C:/Kien/Mobile/orm/android` (gradlew, gradle files, keystore, 31 Kotlin files).
Per-question data already exists in `OmrProcessor` (`result.answers`) and is used
to derive the exported `answered`/`multi` counts. Native export is therefore a
small code change in `HeadlessBenchmark.writeJson` plus a rebuild, not a blocker.

This note separates reruns that can be done from the current workspace from
reruns that require the Redmi Note 13 Pro+ and a buildable Android project.

## Current State

- `adb` is installed at:
  - `C:/Users/Anh Kien/AppData/Local/Android/Sdk/platform-tools/adb.exe`
  - `C:/Android/Sdk/platform-tools/adb.exe`
- `adb devices` reported no connected Android device on 2026-05-28. On
  2026-05-29, Redmi Note 13 Pro+ was connected as `23090RA98G` and Chrome
  `148.0.7778.178` was driven through CDP.
- The complete buildable Android project is the sibling folder
  `C:/Kien/Mobile/orm/android` (gradlew, `build.gradle.kts`, `settings.gradle.kts`,
  keystore, 31 Kotlin files). The `wasm-omr-mobile/android/` folder inside this
  repo is essentially empty and is not the project.
- Adding per-question export fields is a small code change in the sibling project
  plus a rebuild; the data already exists in `OmrProcessor` (`result.answers`).

## No-Device Work Already Done

- YOLO raw-handoff detection audit:
  - Script: `scripts/summarize_yolo_detection.py`
  - Source: `runs/batch_results/redmi_note13_pro_plus_yolo_raw_n179.json`
  - Outputs:
    - `runs/accuracy_eval/yolo_detection_n179/summary.json`
    - `runs/accuracy_eval/yolo_detection_n179/per_sheet_detection.csv`
  - Result: `154/179` sheets have `yolo_detected=true`; `179/179` use raw
    handoff; fallback used is `0`.
- Artifact manifest:
  - `docs/ARTIFACT_MANIFEST_5PAGE.md`

## Redmi Web Reruns Completed on 2026-05-29

- Web CV:
  - Batch JSON: `runs/batch_results/redmi_note13_pro_plus_20260529_cv_n179.json`
  - Resource CSV: `runs/resources/redmi_note13_pro_plus_20260529/resources_redmi_note13_pro_plus_20260529_cv_n179.csv`
  - Result: `cpp_ms=795.2 +/- 118.7` ms over 179 sheets.
- Web YOLO raw:
  - Batch JSON: `runs/batch_results/redmi_note13_pro_plus_20260529_yolo_raw_n179.json`
  - Resource CSV: `runs/resources/redmi_note13_pro_plus_20260529/resources_redmi_note13_pro_plus_20260529_yolo_raw_n179.csv`
  - Result: `cpp_ms=820.1 +/- 75.5` ms, `yolo_ms=309.4 +/- 39.0` ms,
    `worker_total_ms=1130.1 +/- 89.0` ms over 179 sheets.
- YOLO detection summary:
  - `runs/accuracy_eval/yolo_detection_20260529_n179/summary.json`
  - `154/179` `yolo_detected=true`; `179/179` raw handoff; `0` fallback used.

## Remaining Reruns and Source-Dependent Work

### 1. Native Per-Question Export

Goal: make Native Android a recognition baseline, not only a latency/resource
baseline.

Required code change:

- Extend the Native benchmark JSON writer to export:
  - sheet path/id
  - `auto_q01` through `auto_q60`
  - `answered`, `suspicious`, `multi`
  - `mssv`, `examCode`, validity flags
  - `ort_ms`, detector stage time, `cpp_ms`/OMR time, `e2e_ms`
  - provider (`CV`, `CPU1T`, `CPU4T`, `NNAPI`)

Where to make the change:

- Sibling project `C:/Kien/Mobile/orm/android`. Add `auto_q01..auto_q60`
  (from `result.answers`) to `HeadlessBenchmark.writeJson` and the matching
  `SheetCapture` fields. The arrays are already computed for the `answered`/`multi`
  counts in `CvBenchmarkRunner.processSheet` and `YoloBenchmarkRunner.processSheet`.

Device steps after code is available:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
cd android
.\gradlew.bat assembleTraditionalDebug assembleYoloDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\traditional\debug\app-traditional-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\yolo\debug\app-yolo-debug.apk
```

Then run the in-app/headless benchmark and pull JSON outputs from app external
files into `runs/batch_results/`.

### 2. Same-Boundary Web vs Native Latency

Goal: compare Web and Native using matching component timers.

Web required fields:

- image/decode time if available
- YOLO inference/stage time
- OMR/CV time
- post-processing/write/debug time
- worker total and browser wall-time if possible

Native required fields:

- decode time
- ORT/detector stage time
- OMR/CV time
- debug write time
- end-to-end total

Redmi Web setup already verified on 2026-05-29:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse tcp:8080 tcp:8080
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:9222 localabstract:chrome_devtools_remote
python web/server.py 8080 --dataset=Dataset_OMR_classified
python scripts/drive_batch.py redmi_note13_pro_plus cv --cdp-port 9222 --base-url http://localhost:8080
python scripts/drive_batch.py redmi_note13_pro_plus yolo --cdp-port 9222 --base-url http://localhost:8080
```

### 3. Mobile Web Stage Decomposition on 179 Sheets

Goal: replace the older `N=43` Mobile Web stage profile with same-artifact
179-sheet stage attribution.

Minimum output:

- `yolo_ms` mean/SD
- `cpp_ms` or OMR-stage mean/SD
- worker total mean/SD
- browser/runtime metadata

The 2026-05-29 Web raw-handoff JSON contains `yolo_ms`, `cpp_ms`, and
`worker_total_ms`. If browser wall-time or finer decode/post-processing fields
are required, the Web batch logger must be extended before another rerun. Native
same-boundary fields still require a buildable Android project.

## Reruns That Can Be Done on Vivobook Only

Vivobook can validate scripts and output schemas, but it cannot replace the
mobile evidence:

- Web CV/YOLO dry-runs through Chrome CDP.
- Resource CSV schema validation.
- Figure regeneration.
- Manifest and table generation checks.

Vivobook-only reruns should be treated as debugging/preflight unless the paper
explicitly discusses the Vivobook row.
