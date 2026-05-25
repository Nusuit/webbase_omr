# Agent Context: Web-based Edge OMR — Code Review & Missing Data Collection

## Project Overview

This is a Vietnamese IEEE paper on Optical Mark Recognition (OMR) executed entirely in the browser using WebAssembly and WebGPU. The research compares two methods — Traditional CV (OpenCV.js/WASM) and YOLO+CV (YOLOv8n via ONNX Runtime Web with WebGPU backend) — across three main runtime platforms: **Desktop Web**, **Mobile Web**, and **Native Android**, with Python/PC serving as a reference baseline.

**Paper title:** "Triển khai thuật toán chấm thi trắc nghiệm qua hình ảnh xử lý biên trên nền tảng Web"

---

## Codebase Location

```
c:\Kien\Mobile\orm\wasm-omr-mobile\web\
```

Key files:
- `index.html` — Main UI
- `js/app.js` — Application orchestration, benchmark mode, UI logic
- `js/db.js` — IndexedDB persistence (session logs, results)
- `js/wasm-bridge.js` — Main thread ↔ Worker communication
- `js/worker-protocol.js` — Shared message types
- `wasm/omr.js` + `wasm/omr.wasm` — Emscripten-compiled C++ OMR pipeline
- `js/ort.min.js` — ONNX Runtime Web (WebGPU backend)
- `models/paper_detect.onnx` — YOLOv8n model (4 classes, see below)

Branch structure:
- `staging` — Traditional CV only worker
- `v2-yolo` — YOLO+CV worker with WebGPU inference
- `testing` (target) — Unified branch with side-by-side UI, benchmark mode, per-stage timing

---

## YOLO Model Details

The model `paper_detect.onnx` is YOLOv8n fine-tuned with **4 classes** (NOT 2):
1. `corner_marker` — 4 alignment marks at sheet corners
2. `info_boundary` — Student info and exam code region
3. `questions_1_20` — Question region rows 1–20
4. `questions_21_60` — Question region rows 21–60

The model **does NOT detect individual bubbles** — it detects boundary regions. Individual bubble fill states are determined by the CV pipeline (adaptive threshold + contour detection) applied inside the detected regions.

Input: 640×640 resized frame → ONNX Runtime Web → WebGPU backend → NMS → coordinate mapping → bubble grid extraction → grading output.

---

## Architecture

```
Main Thread
    │── postMessage ──► Web Worker
                            ├── WASM Runtime (OpenCV.js / Emscripten C++)
                            │       └── Grayscale → Blur → Canny → Homography
                            │           → Adaptive Threshold → Bubble Detection
                            └── WebGPU Runtime (ONNX Runtime Web)
                                    └── Resize → Inference → NMS → Cell Mapping
                                        → Grade Output → Device Hardware
```

Critical implementation notes:
- **ArrayBuffer transfer**: Must use `ArrayBuffer.slice(0)` before each postMessage to both workers to avoid neutered buffer errors
- **ONNX session lifecycle**: Session must stay warm across all N benchmark runs. Only dispose tensors per run, NOT the session
- **Module.print**: Use to intercept Emscripten C++ stdout (NOT console.log)
- **WebGPU fallback**: If WebGPU unavailable, ONNX falls back to WASM backend — this degrades inference 21× (142ms → ~1,170ms). Paper's key finding depends on WebGPU being active

---

## What the Testing Branch Must Deliver

### 1. Side-by-side UI
- Process each sheet simultaneously through CV-only and YOLO+CV pipelines
- Display both results side-by-side for visual comparison
- Worker isolation: separate worker instances for each method to avoid state contamination

### 2. Benchmark Mode (n=30)
- Run 30 sequential passes per method on the same input
- ONNX session stays warm throughout all 30 runs (never disposed between runs)
- Per-stage timing via `performance.now()` for each run:
  - Stage 1: Preprocessing (grayscale, blur, Canny, homography)
  - Stage 2: CV detection (adaptive threshold, bubble detection)
  - Stage 3 (YOLO only): Resize + WebGPU inference + NMS
  - Stage 4: Answer cell mapping + grading
- Collect: mean ± SD for each stage and total
- Output: JSON log per session saved to IndexedDB

### 3. Verified Reference Output (Ground Truth Mechanism)
- First run: system outputs detected answer sheet result + annotated preview image
- Researcher visually confirms this output is correct (boundary detection + bubble fill states all look right)
- On confirmation, the output is stored as the **Verified Reference Output** (bitmask: 60 boolean values for selected answers per sheet)
- Subsequent runs: compare output bitmask against Verified Reference
- Compute bubble-level Precision / Recall / F1:
  - TP: bubble correctly identified as filled
  - FP: bubble incorrectly identified as filled (was empty)
  - FN: filled bubble missed (detected as empty)
  - TN: empty bubble correctly identified as empty
  - Total bubbles: 60 questions × 5 choices = 300 per sheet
- Also compute question-level accuracy: question correct iff all 5 bubbles classified correctly
- Handle suspicious marking: if >1 bubble filled in same question, flag as "tô nhiều" (multi-mark)

### 4. Resource Monitoring (Multi-platform Synchronized)
Currently the resource collection methods differ per platform:
- Desktop Web: Chrome Task Manager
- Mobile Web: Chrome Task Manager (mobile)
- Native Android: `adb top` / `adb dumpsys cpuinfo`
- PC Baseline: Windows Task Manager

**What needs to be implemented in the web app:**
- `performance.memory` (JS Heap used/total) — available in Chrome, read via `performance.memory.usedJSHeapSize`
- CPU: NOT directly accessible from JS. Must be measured externally. The app should log frame timestamps so external measurement can be correlated
- RAM: JS heap via `performance.memory` + note that WASM heap is separate (~1.9 GB allocated but not all used)
- GPU: WebGPU does not expose GPU memory usage via JS API — must note this as a measurement gap in the paper
- Battery: Not measurable from web JS (Battery API deprecated). Must be measured externally on device

**Recommendation**: The app should export a per-run JSON log with:
```json
{
  "method": "cv" | "yolo",
  "platform": "desktop-web" | "mobile-web",
  "run_index": 0..29,
  "stage_times_ms": { "preprocess": X, "cv_detect": Y, "yolo_infer": Z, "grade": W },
  "total_ms": T,
  "js_heap_used_mb": H,
  "js_heap_total_mb": HT,
  "answer_bitmask": [true/false × 300],
  "question_accuracy": 0.964,
  "bubble_precision": 0.962,
  "bubble_recall": 0.979,
  "bubble_f1": 0.970
}
```

---

## Metrics Still Missing for the Paper

### A. Timing Data (Bảng II — Inference Time)
Currently have: mean inference time per platform/method
Still need: **±SD values** for each cell in Bảng II and Bảng III
- Source: 30-run benchmark logs
- Need: per-stage breakdown in addition to total

### B. Bubble-level Accuracy (Bảng I-ter)
Currently in paper: TP/FP/FN/TN, P/R/F1 values for 100-sheet test set
These numbers need to come from the Verified Reference Output mechanism
- Currently the values in the paper (TP=5874, etc.) are ESTIMATED — need experimental confirmation
- The testing branch must produce these numbers from the 100-sheet test run
- Run on all 3 platforms to confirm method consistency

### C. RAM / JS Heap (Bảng III)
Need: JS heap measurements for Desktop Web and Mobile Web during benchmark runs
- Use `performance.memory.usedJSHeapSize` (Chrome only, requires `--enable-precise-memory-info` flag or may be available by default)
- Measure: baseline (before processing), peak (during WASM processing), after GC
- Note: WASM heap (~1.9 GB) is reported separately via Module.HEAP8.byteLength

### D. Cross-device Consistency Check (Bảng II-ter)
Paper claims results were extended to Samsung Galaxy A15 and Firefox
Need: actual timing data for these secondary devices/browsers
- Samsung Galaxy A15 + Chrome: timing + JS heap
- Redmi Note 13 Pro+ + Firefox: timing + JS heap (note: WebGPU may not be available in Firefox — this affects YOLO method)

### E. Battery Consumption
Paper reports: Mobile Web ~4.8%/100 sheets, Native Android ~1.3%/100 sheets
Need: Confirmation these numbers are from actual measurement (not estimated)
Source: External measurement during benchmark runs on charged device
- Measurement protocol: start at 100%, run 100 sheets continuously, read battery % after

---

## Key Statistical Facts for Paper Consistency

With 5 answer choices per question (60 questions × 5 = 300 bubbles/sheet):
- Total bubbles for 100-sheet test set: **30,000**
- TP/FP/FN values: **unchanged** from 4-choice scenario (same filled bubbles)
- TN values change: +6,000 each (100 sheets × 60 questions × 1 extra choice)
  - CV: TN = 23,766 (was 17,766)
  - YOLO: TN = 23,898 (was 17,898)
- Precision/Recall/F1: **mathematically unchanged**
- Bubble-level accuracy: CV = 98.8%, YOLO = 99.0% (improved from 98.5%)
- Question-level accuracy: still 96.4% (unchanged, depends on question correct/wrong not bubble count)

---

## Platform Naming — Use Consistently

| Platform | Correct Label | Wrong (avoid) |
|---|---|---|
| Chrome on PC/laptop | Desktop Web | "Web", "PC Web" |
| Chrome on Android phone | Mobile Web | "Mobile", "Android Web" |
| Native Android app (ONNX CPU) | Native Android | "Android Native", "Native" |
| Python on PC | PC Baseline / Python/PC | "PC", "Python" |

---

## What the Agent Should Check

1. **[x] Does the testing branch exist?** Yes, we are actively operating in testing.
2. **[x] Is the ArrayBuffer.slice(0) fix in place?** We bypassed this entirely by passing the File Blob directly to each worker to internally construct via `createImageBitmap` — entirely eliminating ArrayBuffer neutering issues.
3. **[x] Is the ONNX session persisted across benchmark runs?** Yes, session remains warm.
4. **[x] Does the benchmark mode collect per-stage timing?** Yes, `testRuns` architecture computes loops and yields mean/SD metrics.
5. **[x] Is there a Verified Reference Output mechanism?** Yes, just implemented in Addendum 2. UI now intercepts execution at Runtime=1 and warns on empty/uncertain fields prior to `✓ Set Reference`.
6. **[x] Is `performance.memory` being sampled during runs?** Yes, JS heap limit vs execution used metric is plotted in logs.
7. **[x] Does the app export logs?** Yes, the CSV export mechanism (Bench + Bubbles) has superseded IndexedDB for immediate academic plotting.
8. **[x] Is suspicious marking (multi-bubble) handled?** Yes, computed and logged.
9. **[x] Is the YOLO inference backend confirmed as WebGPU?** Yes, `worker-yolo.js` instantiates ONNX with WebGPU.
10. **[x] Does the side-by-side UI exist?** Yes, and functions fully asynchronously without blocking browser threads.

---

## Paper → Code Sync Issues to Verify

- Paper claims "khung hình được resize về 640×640" — verify ONNX input tensor is actually 640×640
- Paper claims "NMS loại bỏ các hộp giới hạn trùng lặp" — verify NMS is implemented (either in JS post-processing or inside the ONNX model graph)
- Paper claims inference time 142ms (warm, WebGPU, mobile) — this should be reproducible in benchmark mode
- Paper states WASM pipeline is the bottleneck (~90%+ of total time) — per-stage timing will confirm this
- Paper cites 4.8% battery per 100 sheets (Mobile Web) vs 1.3% (Native Android) — need protocol confirmation

---

## Files to Deliver Back to Paper Authors

After running the testing branch, use the built-in UI buttons to download:
1. `benchmark_stats_CV_n3000.csv` — Full E2E and C++ stage timings, memory usage, and aggregated Confusion Matrices.
2. `benchmark_stats_YOLO_n3000.csv` — Full E2E, YOLO WebGPU inference, C++ stage timings, and memory.
3. `benchmark_bubbles_CV_n3000.csv` — Raw 30,000 bubble evaluations mapped to Ground Truth Reference structure.
4. `benchmark_bubbles_YOLO_n3000.csv` — Raw 30,000 bubble evaluations mapped to Ground Truth Reference structure.
5. Screenshots of side-by-side UI showing detection quality.

These parameters map natively into **Bảng I-ter**, **Bảng II**, and **Bảng III**.
