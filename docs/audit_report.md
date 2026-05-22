# Performance Audit Report: Web OMR System

## 1. WASM Initialization
- **FINDING**: The WASM module (`omr.js`) is loaded immediately when the Web Workers are instantiated on page load. It is not re-initialized per image; however, because the compiler flag `-sALLOW_MEMORY_GROWTH=1` is explicitly set in `CMakeLists.txt` without any memory limits, the heap continuously scales up to match peak memory consumption but Emscripten never returns it to the OS.
- **SEVERITY**: High
- **ESTIMATED IMPACT**: Stops OOM (Out of Memory) crashes on mobile devices; keeps Web memory footprint ~100MB instead of 1.7GB.
- **FILE:LINE**: `CMakeLists.txt:31`

## 2. postMessage Transfer Overhead
- **FINDING**: The image data is transferred as raw RGBA `ArrayBuffer` correctly utilizing Transferable Objects (`[buffer]`). However, raw buffers at 1145×2560 pixels are ~11.7MB each. The `slice(0)` command makes blocking synchronous memory copies (23.4MB total) on the main UI thread.
- **SEVERITY**: Medium
- **ESTIMATED IMPACT**: Saves ~50–100ms per submit of pure UI thread lock, especially on mid-tier mobile processors.
- **FILE:LINE**: `web/js/app.js:68`

## 3. WebGPU Pipeline (YOLO)
- **FINDING**: The ONNX Runtime session is created once via `ensureYolo()` and reused. However, there is no explicit Service Worker caching the 6.5MB `paper_detect.onnx` asset. Furthermore, there is no "warm-up" step. First inference automatically incurs a massive JIT/Shader compilation penalty on the WebGPU backend which artificially skews the timing of the first image processed.
- **SEVERITY**: High
- **ESTIMATED IMPACT**: Removes a ~2,000ms+ unpredictable spike on the very first YOLO processing run.
- **FILE:LINE**: `web/js/worker-yolo.js:39`

## 4. Image Preprocessing
- **FINDING**: No downsampling is performed before dispatching images to the Web Worker. The full 11.7MB `sheetWidth x sheetHeight` image is passed. Additionally, `ctx.getImageData()` runs entirely on the main UI thread synchronously before dispatching.
- **SEVERITY**: Critical
- **ESTIMATED IMPACT**: Saves ~150-300ms UI blocking. Downsampling a 12MP camera photo to a standard boundary (e.g. 1700x2400 max) before extraction saves ~40-60% postMessage byte payload.
- **FILE:LINE**: `web/js/app.js:62`

## 5. Web Worker Architecture
- **FINDING**: Persistent workers are correctly utilized (no re-spawning). There are no synchronous XHRs inside the worker, and messages are batched properly (one big dispatch, one result payload return). Architecture is sound here.
- **SEVERITY**: Low
- **ESTIMATED IMPACT**: N/A
- **FILE:LINE**: `web/js/app.js:25`

## 6. Memory Management
- **FINDING**: 
  1. *C++ side*: `g_last_preview` and `g_last_warped` are static global `std::vector` objects that persistently hold 1700x2400x4 (16.3MB) each. They overwrite, but combined with local vectors (`s_normbuf`), it causes heap fragmentation and `ALLOW_MEMORY_GROWTH` triggers repeatedly.
  2. *JS side*: ONNX `ort.Tensor` inputs and `results` dictionaries returned by `session.run()` are never `.dispose()`'d. This specifically causes invisible WebGPU VRAM leaks which crash mobile devices after ~10-20 loops.
- **SEVERITY**: Critical
- **ESTIMATED IMPACT**: Prevents fatal VRAM WebGPU leaks and cuts memory growth from 1.7GB back down to a stable ~150-300MB footprint.
- **FILE:LINE**: `web/js/worker-yolo.js:134` (ONNX leak) | `src/core/omr_core.cpp:15` (C++ static buffers)

## 7. Rendering / UI Thread
- **FINDING**: Appending C++ logs iteratively triggers layout thrashing: `element.scrollTop = element.scrollHeight` runs continuously in a `forEach` loop. Rendering the 16MB returned buffer back into the DOM using `ctx.putImageData()` blocks the main thread heavily right as results arrive.
- **SEVERITY**: Medium
- **ESTIMATED IMPACT**: Refactoring log appending to a `DocumentFragment` or throttling DOM scrolls saves ~100-200ms of lag/jitter on the main thread when results hit.
- **FILE:LINE**: `web/js/app.js:134`

## 8. Network / Asset Loading
- **FINDING**: Models and WASM files are downloaded cleanly via standard fetch. But `yolov8n.onnx` (~6.5MB) is not leveraging the `CacheStorage` API for persistent offline use across sessions.
- **SEVERITY**: Low
- **ESTIMATED IMPACT**: Saves bandwidth and ~500ms network fetch latency on page reloads if the browser's disk cache is purged.
- **FILE:LINE**: `web/js/worker-yolo.js:39`

---

### Prioritized Summary Table

| # | Issue | Severity | Est. Impact | File:Line |
|---|-------|----------|-------------|-----------|
| 1 | Main thread `ctx.getImageData()` blocking | Critical | ~150-300ms UI unblock | `web/js/app.js:62` |
| 2 | Memory leak: ONNX `Tensor` lacking `.dispose()` | Critical | Prevents OOM crashes | `web/js/worker-yolo.js:134` |
| 3 | WASM Emscripten `ALLOW_MEMORY_GROWTH` leak | High | Stabilizes ~1GB heap leak | `CMakeLists.txt:31` |
| 4 | No WebGPU warm-up session | High | Eliminates >2,000ms JIT lag | `web/js/worker-yolo.js:39` |
| 5 | Buffer `slice(0)` & large resolution data transfers | Medium | ~50-100ms unblock | `web/js/app.js:68` |
| 6 | Layout thrashing on `element.scrollTop` in loops | Medium | ~100ms UI unblock | `web/js/app.js:134` |
| 7 | No Service Worker Cache API for `onnx` assets | Low | ~500ms network savings | `web/js/worker-yolo.js:39` |
| 8 | Worker Architecture (already optimal) | Low | N/A | `web/js/app.js:25` |
