# wasm-omr-mobile

Fully client-side **Optical Mark Recognition (OMR)** for the browser, and a
**cross-platform performance benchmark** comparing Web Edge (WebAssembly +
WebGPU) against a Native Android baseline.

All computer-vision and neural-inference stages run **on-device** — answer-sheet
images are never uploaded to a server. The same URL-deployed bundle runs on
desktop and mobile browsers.

## What this is

- A complete OMR pipeline with two interchangeable paths:
  - **CV-only** — C++/OpenCV (grayscale, homography normalization, adaptive
    threshold, fixed-grid bubble reading) compiled to **WebAssembly** via
    Emscripten.
  - **YOLO + CV** — YOLOv8n region localization on **WebGPU** (ONNX Runtime
    Web), handed off to the same CV recognizer.
- A **Native Android** build (OpenCV Android SDK + ONNX Runtime Android with
  CPU-1T / CPU-4T / **NNAPI** providers) used as a latency/resource baseline.
- A reproducible benchmark across **six Web hosts + one Native device** on an
  identical **N=179** answer-sheet set and a single shared ONNX model.

## Key results (reviewed N=179 set)

- **Recognition:** the CV path reaches **99.00%** question accuracy. YOLO
  *raw-handoff* preserves that score; YOLO *hard-mask* cropping degrades it to
  **63.70–74.40%**, so detector-guided cropping is treated as a separate
  algorithm, not a free preprocessor.
- **Latency (same Redmi Note 13 Pro+):** Mobile Web YOLO raw-handoff is
  **998 ± 75 ms** worker-total vs **264 ± 17 ms** file-based E2E for Native
  NNAPI.
- **The bottleneck is the Wasm CV stage, not ML inference.** WebGPU makes YOLO
  inference secondary; on the same `cpp_ms` boundary the Web CV stage is
  **~7.0×** slower than native. Closing the Wasm execution gap — not
  accelerating inference — is the path to competitive Web-Edge performance.

Full method and tables: `omr_etc2026.tex` (paper, ≤6 pages).

## Repository layout

```
src/core/            C++ OMR core (normalize, threshold, bubble reading) + Wasm bindings
web/                 Browser app, Web Workers (worker-cv.js / worker-yolo.js), Wasm/ONNX assets
web/batch-detect.html   Headless batch runner driven over Chrome DevTools Protocol
web/server.py        Static server with cross-origin-isolation headers (SharedArrayBuffer)
scripts/             Benchmark drivers + plotting (run_platform.py, drive_mobile_batch.py, plot_*.py)
runs/                Benchmark artifacts: batch_results/ (per-sheet JSON), resources/ (CPU/RAM CSV)
full_label_yolo/     YOLO-detection training set (4 classes), images + labels + dataset.yaml
omr_etc2026.tex      Research paper (source of truth)
android/             Native Android app lives in the sibling folder ../android
```

## Build the Wasm core

```bash
emcmake cmake -S . -B build
cmake --build build      # -> web/wasm/omr.js, web/wasm/omr.wasm
```

## Run the web app

```bash
python web/server.py 8080 --dataset=Dataset_OMR_classified
```

Open `http://localhost:8080`. The server sets `Cross-Origin-Opener-Policy`,
`Cross-Origin-Embedder-Policy`, and `Cross-Origin-Resource-Policy` so WASM
SIMD + threads (SharedArrayBuffer) are available.

## Reproduce the benchmark

PC / laptop Web Edge (launches Chrome, serves the dataset, runs CV + YOLO over CDP):

```bash
python scripts/run_platform.py <platform_tag>
# -> runs/batch_results/<tag>_{cv,yolo}_n179.json
```

Mobile Web (phone Chrome over `adb reverse`/`adb forward`):

```bash
adb reverse tcp:8080 tcp:8080
adb forward tcp:9222 localabstract:chrome_devtools_remote
python scripts/drive_mobile_batch.py yolo <out.csv> --yolo-mask raw --out-json <out.json>
```

Native Android (headless `BenchmarkActivity`, dataset staged in scoped storage):

```bash
adb shell am start -n com.gradesnap.omr/.BenchmarkActivity \
  --es headless true --es method yolo --es manifest <device/manifest.json>
# YOLO runs CPU-1T, CPU-4T, and NNAPI; results land in the app Documents dir
```

> Note: per-sheet **latency is sensitive to host power/thermal state**,
> especially on thin laptops (observed up to ~2× spread). Measure under a fixed
> power plan; the paper reports the Vivobook host as a five-run mean.

## Dataset & detection classes

The detector localizes four regions per sheet:
`paper_region`, `info_region` (ID + key code), `questions_1_20`,
`questions_21_60`. The recognizer is deterministic (homography + fixed grid),
so YOLO is used for localization only — the answer string always comes from the
CV reader.
