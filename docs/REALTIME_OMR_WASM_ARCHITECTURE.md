# Real-time Optical Mark Recognition on Mobile Edge: A WebAssembly-based Zero-Server Approach

## 1. Research Scope and Problem Statement

This system targets real-time Optical Mark Recognition (OMR) for paper-based multiple-choice exams on commodity mobile devices, with strict **zero-server** constraints:

- No image upload to backend services.
- All inference and scoring happen on-device in browser runtime.
- User privacy is preserved by design.
- The pipeline remains operational in low-connectivity or offline environments.

Primary objective:
- Achieve practical on-device OMR quality comparable to existing native mobile implementation while preserving low latency and privacy.

Secondary objective:
- Unify OMR core logic across platforms (Android and Web) using a shared C++ core.

## 2. Design Principles

- Edge-first computation: image processing, detection, and scoring run on local CPU.
- Zero-server architecture: no required remote API in core workflow.
- Deterministic processing: algorithmic threshold and grid logic are explicit and reproducible.
- Progressive enhancement: start from robust static-image processing, then extend to live camera mode.
- Shared-core strategy: C++ core with platform-specific wrappers (JNI and Emscripten).

## 2.1 What Changes from Native Mobile App to Web Mobile

This section highlights the non-trivial architectural shifts when moving from Android native app to browser-based mobile runtime.

### A. Threading and Execution Model

Native Android:
- Image processing is typically dispatched through coroutines/background threads with direct access to OpenCV Java/NDK objects.
- UI and compute can share process memory with relatively low marshalling overhead.

Web mobile:
- Main thread must stay responsive for rendering/input; heavy OMR must be offloaded to Web Worker.
- Worker and UI communicate by message passing (`postMessage`) and transferable buffers.
- Wasm execution is isolated in worker context, not in the DOM thread.

Implication:
- Worker protocol design becomes a first-class system component (not optional glue code).

### B. Memory Ownership and Copy Costs

Native Android:
- `Bitmap -> Mat` conversion stays in-process and memory lifecycle is controlled by Kotlin/JNI + GC/RAII.

Web mobile:
- Typical path is `ImageBitmap/Canvas -> ImageData (RGBA) -> Worker -> Wasm HEAP`.
- Without careful transfer/reuse, repeated copies increase latency and memory pressure.
- Wasm memory (`HEAPU8`, `HEAP32`) is manually managed through `_malloc/_free`.

Implication:
- Buffer pooling and transferables are mandatory for stable FPS and avoiding mobile browser OOM crashes.

### C. Runtime and Engine Variability

Native Android:
- Device fragmentation exists, but runtime model is stable (ART + Android APIs).

Web mobile:
- Behavior depends on browser engine and version (Chromium, WebKit, Gecko).
- Feature support (SIMD, threads, camera APIs, performance counters) is engine-dependent.
- Thermal throttling and memory limits are often stricter in browser tabs than native apps.

Implication:
- Performance claims must be reported per browser-engine matrix, not only per device model.

### D. Packaging and Deployment

Native Android:
- APK distribution, fixed dependency graph, explicit ABI packaging.

Web mobile:
- Static assets (`.js/.wasm`) delivered by web server/CDN.
- Browser caching, service worker behavior, and security headers affect runtime capability.

Implication:
- DevOps/web serving strategy directly affects OMR runtime behavior.

## 3. System Architecture Overview

The runtime architecture is a 3-layer model:

1. Presentation Layer (Main Thread UI)
- Camera control and upload UX.
- Canvas rendering for input/output visualization.
- Results display and local project management.

2. Compute Orchestration Layer (Web Worker)
- Message-driven command execution.
- Wasm module lifecycle management.
- Frame/sheet processing isolation from UI thread.

3. Algorithm Layer (C++ Core compiled to Wasm)
- Pixel-level preprocessing.
- Numeric and multiple-choice bubble detection.
- Suspicious-mark heuristics.
- Stable C ABI for language/runtime interoperability.

### 3.1 Worker-Centric Control Flow (Web-specific)

Live mode:
1. Main thread captures preview frame from camera stream.
2. Frame buffer is transferred to worker (`omr/process-frame`).
3. Worker invokes Wasm core and returns `omr/frame-result`.
4. Main thread renders processed frame and overlays.

Sheet mode:
1. Main thread resizes upload image to normalized working size.
2. Full RGBA buffer is transferred to worker (`omr/process-sheet`).
3. Worker runs sheet pipeline in Wasm and decodes ABI output.
4. Main thread renders binary/debug image and structured OMR JSON.

Rationale:
- This avoids long tasks on UI thread and prevents camera/render jank on mid-range devices.

## 4. Repository Mapping (Current Implementation)

Core and Wasm bridge:
- `wasm-omr-mobile/src/core/omr_core.h`
- `wasm-omr-mobile/src/core/omr_core.cpp`
- `wasm-omr-mobile/src/core/omr_bindings.h`
- `wasm-omr-mobile/src/core/omr_bindings.cpp`
- `wasm-omr-mobile/CMakeLists.txt`

Worker protocol and web runtime:
- `wasm-omr-mobile/web/js/worker-protocol.js`
- `wasm-omr-mobile/web/js/worker.js`
- `wasm-omr-mobile/web/js/wasm-bridge.js`
- `wasm-omr-mobile/web/js/app.js`
- `wasm-omr-mobile/web/index.html`

Reference baseline (native Android release logic):
- `android/app/src/main/java/com/gradesnap/omr/NormalizePaper.kt`
- `android/app/src/main/java/com/gradesnap/omr/BubbleDetector.kt`
- `android/app/src/main/java/com/gradesnap/omr/OmrProcessor.kt`
- `android/app/src/main/assets/omr_layout_config.json`

## 5. Processing Pipeline

### 5.1 Input Modes

- Live mode: low-overhead frame processing for immediate visual feedback.
- Upload mode: high-resolution sheet processing (resized to 1700x2400 in current implementation).

### 5.2 Core Steps

1. RGBA to grayscale conversion.
2. Binary inverse thresholding.
3. Region-grid sampling with layout-aware cell bounds.
4. White-ratio computation per bubble cell.
5. Decision logic:
- Numeric fields (MSSV, exam code): exactly one selected row per column.
- Questions: one or multiple selected options (bitmask encoding).
- Suspicious marks: multi-fill or weak single-fill.

### 5.3 Output Semantics

Current Wasm sheet output includes:
- `mssv_valid`, `mssv` digits.
- `key_valid`, `examCode` digits.
- `answers[1..60]` as option bitmasks and decoded labels.
- `suspicious` flags per question.

## 6. Worker Protocol Contract

Protocol version: `v2`.

Message types:
- `omr/init` -> initialize Wasm.
- `omr/ready` -> Wasm ready signal.
- `omr/process-frame` -> live frame processing.
- `omr/frame-result` -> live result payload.
- `omr/process-sheet` -> full-sheet OMR request.
- `omr/sheet-result` -> structured OMR output.
- `omr/error` -> typed runtime error.

Why this matters for publication:
- Enables clear separation of concerns and reproducibility of runtime behavior.
- Provides a platform-agnostic interface suitable for controlled experiments.

## 7. C++ Core API and ABI

Exported symbols:
- `_omr_process_frame`
- `_omr_process_frame_v2`
- `_omr_process_sheet`
- `_malloc`
- `_free`

Sheet output buffer format (`int[132]`):
- `[0]` status
- `[1]` mssv_valid
- `[2..7]` mssv digits
- `[8]` key_valid
- `[9..11]` key digits
- `[12..71]` answer masks (q1..q60)
- `[72..131]` suspicious flags (q1..q60)

This ABI is intentionally flat for low-overhead interop and stable benchmarking.

## 8. Data and State Management

- Upload artifacts are cached in IndexedDB (`UploadStore`).
- Intermediate and final results are rendered in-memory and can be serialized for export.
- No mandatory server synchronization in core path.

Recommended extension for experimental rigor:
- Persist full run metadata per scan: timestamp, device model, browser version, latency stats.

## 8.1 CPU/RAM Budgeting and Browser Constraints

To make web-mobile OMR production-grade, resource management must be explicit:

### CPU

- Avoid processing every camera frame; use adaptive cadence (frame skip under load).
- Separate \"preview pipeline\" from \"final grading pipeline\".
- Keep worker hot and reuse initialized Wasm module to avoid repeated startup cost.

### RAM

- Reuse Wasm input/output buffers (`_malloc` once, grow only when needed).
- Prefer transferable `ArrayBuffer` to reduce structured-clone copies.
- Release temporary canvases/bitmaps after sheet processing.

### Browser engine behavior

- Track compatibility for: `getUserMedia`, Offscreen/worker behavior, SIMD/threaded Wasm support.
- Expect different GC timing and memory caps across engines.
- Validate thermal and background-tab throttling behavior in long sessions.

Minimum reporting set for experiments:
- Device model, OS version, browser name/version, engine family.
- Median and P95 latency for `process-frame` and `process-sheet`.
- Peak memory estimate (JS heap + Wasm heap proxy metrics).

## 9. Performance Model

### 9.1 Latency Budget (Target)

- Live preview path: sub-100 ms user-perceived update loop.
- Full-sheet processing: device-dependent; target below 500 ms on modern mid-range phones.

### 9.2 Main Bottlenecks

- Pixel traversal and thresholding.
- Cell ratio evaluation over all regions.
- Canvas bitmap transfer overhead between main thread and worker.

### 9.3 Optimization Levers

- SIMD-enabled Wasm builds.
- Worker-side memory reuse and pooled buffers.
- Frame skipping / adaptive cadence in live mode.
- Optional threaded Wasm (with COOP/COEP headers).

### 9.4 Browser-Engine Evaluation Matrix (Recommended)

At minimum, benchmark these environments separately:

1. Android Chrome (Chromium engine).
2. Android Firefox (Gecko engine).
3. iOS Safari (WebKit engine).

For each environment, report:
- First-load initialization time (Wasm instantiation).
- Steady-state live processing latency.
- Full-sheet processing latency.
- Thermal drift after continuous use (for example, 3-5 minutes).

## 10. Accuracy and Validation Strategy

### 10.1 Baseline

Native Android release output is the reference baseline for parity checks.

### 10.2 Metrics

- Bubble-level precision/recall.
- Question-level accuracy.
- Exam-level score deviation.
- MSSV and exam-code exact-match rate.
- Suspicious-flag agreement rate.

### 10.3 Validation Protocol

- Build a fixed golden dataset from representative exam sheets.
- Compare Wasm output against Android baseline per field.
- Stratify by blur, skew, illumination, and print quality conditions.

## 11. Security and Privacy Analysis

### 11.1 Privacy Advantages

- No image exfiltration in default workflow.
- Reduced data governance overhead compared to server-based OCR.

### 11.2 Remaining Risks

- Client-side local storage exposure on compromised devices.
- Potential side-channel leakage if third-party scripts are introduced.

### 11.3 Hardening Recommendations

- Strict CSP and dependency pinning.
- Isolated worker origin policy.
- Optional local encryption for persisted scan records.

## 12. Threats to Validity (For Paper)

- Device heterogeneity may impact timing and numerical stability.
- Browser engine differences can influence memory and Wasm performance.
- Current normalization parity with Android is still under migration.

Mitigation:
- Report hardware/software matrix explicitly.
- Publish build flags and benchmark scripts.
- Use deterministic dataset and fixed protocol versions.

## 13. Current Gaps and Planned Extensions

Current implementation status:
- Completed: shared C++ Wasm core scaffold, worker protocol v2, sheet-level detection outputs.
- In progress: full normalization parity with native Android (`NormalizePaper.kt` equivalent).
- Planned: robust adaptive thresholding (Otsu-like) and geometric correction in C++ core.

## 14. Reproducibility Checklist

For artifact reproducibility in a publication package, include:

1. Source tree and commit hash.
2. Emscripten version and full build command.
3. Browser and mobile device matrix.
4. Golden dataset and annotation format.
5. Exact evaluation scripts and metric definitions.
6. Protocol schema (`worker-protocol.js`) and ABI documentation.
7. Runtime logs with latency/accuracy summaries.

## 15. Suggested Paper Contributions Framing

Potential contribution statements:

- A practical zero-server OMR architecture for mobile edge browsers using WebAssembly.
- A worker-isolated, real-time processing design with deterministic C++ ABI outputs.
- A cross-platform migration path from native mobile OMR to shared C++ core.
- Empirical analysis framework balancing privacy, latency, and grading accuracy.

## 16. Citation-Ready Terminology Mapping

- "On-device inference" -> computation inside browser worker + Wasm runtime.
- "Zero-server" -> no mandatory remote processing for core OMR/scoring.
- "Edge intelligence" -> full pipeline execution at capture device.
- "Deterministic grading" -> threshold/grid-based explicit rules, not black-box model inference.

## 17. Practical Next Steps

1. Implement geometric normalization parity with native Android in C++ core.
2. Add dataset-driven parity tests (Android vs Wasm outputs).
3. Profile on target mobile devices and publish latency curves.
4. Finalize export pipeline (CSV/PDF) for end-user workflows.
5. Freeze protocol and ABI for experiment reproducibility.
