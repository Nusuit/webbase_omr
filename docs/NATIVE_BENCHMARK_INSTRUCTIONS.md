# Native Android benchmark — re-run with `dataset_1..5` (N=179)

The PC Web + Mobile Web batches are driven by `scripts/drive_batch.py`
(via Chrome DevTools Protocol). Native Android cannot use the same
driver because it runs through the Android app, not Chrome. Below is
the manual procedure for refreshing the Native benchmark numbers on
the new dataset.

## Prerequisites

- Redmi Note 13 Pro+ with USB debugging ON (already done).
- Native app installed: `android/app/build/outputs/apk/release/app-release.apk`
  (rebuild with `gradlew assembleRelease` if needed).
- Dataset pushed to phone:
  ```bash
  adb push Dataset_OMR_classified /sdcard/Download/Dataset_OMR_classified
  ```
  Confirm: `adb shell ls /sdcard/Download/Dataset_OMR_classified/dataset_{1..5}/"Bài làm" | head`.

## What to capture per provider

Repeat the run for each ONNX provider configuration to refresh the
file-based latency numbers in `tab:latency` (currently CPU-1T
$580{\pm}38$\,ms / CPU-4T $408{\pm}25$\,ms / NNAPI $312{\pm}23$\,ms):

| Provider     | Argument in app | Expected E2E (old) |
|--------------|-----------------|--------------------|
| CPU 1-thread | `--provider=cpu1` | $580{\pm}38$\,ms (YOLO) |
| CPU 4-thread | `--provider=cpu4` | $408{\pm}25$\,ms (YOLO) |
| NNAPI        | `--provider=nnapi` | $312{\pm}23$\,ms (YOLO) |
| pure CV      | `--method=cv`   | $207{\pm}33$\,ms |

## Step-by-step

1. **Start the resource sampler** (background tail of CPU/RAM):
   ```bash
   scripts/pull_native_resources.sh runs/resources/native/resources_native_cv_n179.csv
   ```
   This wraps `adb shell dumpsys meminfo` + `adb shell top` polling at 1Hz.
   Leave running while the app processes sheets.

2. **Launch the app in batch mode** (one of):
   ```bash
   # CV pipeline
   adb shell am start-activity -n com.kien.omr/.BatchActivity \
       --es method cv \
       --es manifest "/sdcard/Download/manifest_n179.json"

   # YOLO + CV with CPU-1T
   adb shell am start-activity -n com.kien.omr/.BatchActivity \
       --es method yolo \
       --es provider cpu1 \
       --es manifest "/sdcard/Download/manifest_n179.json"
   ```
   Or, if the app reads the manifest from a hardcoded path, just push
   the dataset and tap "Run benchmark" in the UI.

3. **Wait for completion**. The app logs each sheet (`adb logcat
   | grep BatchActivity`) and writes `runs/native_results.json` to
   `/sdcard/Download/`.

4. **Pull results** to laptop:
   ```bash
   adb pull /sdcard/Download/native_results_cv_n179.json   runs/batch_results/
   adb pull /sdcard/Download/native_results_yolo_cpu1.json runs/batch_results/
   adb pull /sdcard/Download/native_results_yolo_cpu4.json runs/batch_results/
   adb pull /sdcard/Download/native_results_yolo_nnapi.json runs/batch_results/
   ```

5. **Stop** the resource sampler (Ctrl-C) — it writes the CSV.

## What I need from you to update the paper

Once you have the four JSON files in `runs/batch_results/`, paste the
following summary back into chat and I will update
`omr_etc2026_v8_4_revised.tex` (Table~\ref{tab:latency}, Table~\ref{tab:resources}):

```
                      mean ± stdev   (ms)
Native CV (pure)      ___ ± ___      (N=__)
Native YOLO CPU-1T    ___ ± ___      (N=__)
Native YOLO CPU-4T    ___ ± ___      (N=__)
Native YOLO NNAPI     ___ ± ___      (N=__)

Resource peaks:
CPU-CV   ___ %  (avg ___ %)
RAM-CV   ___ MB
CPU-YOLO ___ %  (avg ___ %)
RAM-YOLO ___ MB
```

If the per-sheet structure of `native_results_*.json` matches the Web
schema (`{status, examCode, mssv, mssvValid, keyValid, answered,
suspicious, multi, cpp_ms}`), I can run
`scripts/aggregate_diag.py` against the Native CV output to cross-check
the diagnostic table — should match PC CV's diagnostic counts (modulo
tiny implementation differences in the OpenCV native build).

## Quick sanity check

Diagnostic counts (marker_fail, suspicious, multi-mark) **should** be
deterministic across PC Web / Mobile Web / Native: same WASM/native
OMR pipeline applied to identical JPEG inputs. If Native diagnostics
deviate substantially from the table already in the paper
(`tab:diag` — generated from PC CV results), that itself is a finding
worth reporting (native OpenCV vs.\ WASM-compiled OpenCV divergence).
