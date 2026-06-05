# C++ OMR Core

This folder contains the C++ OMR implementation compiled into WebAssembly for
the browser pipeline.

## Active Files

- `omr_core.cpp` / `omr_core.h`: deterministic OMR pipeline logic.
- `omr_warp.cpp` / `omr_warp.h`: perspective/warp helpers.
- `omr_bindings.cpp` / `omr_bindings.h`: C ABI exposed to JavaScript/WASM.

## Backup Files

- `omr_core.cpp.before-rule-e.bak`
- `omr_core.cpp.before-stage2-fast.bak`

These backups are retained for source-level provenance. They are not compiled
into the current WASM build and should not be treated as active core logic.

## Paper Boundary

The paper treats this code path as the Web/WASM deterministic CV stage. YOLO
localization can run through WebGPU/ONNX Runtime Web, but normalization,
thresholding, bubble reading, and grading remain in this WASM/C++ path.

When changing this folder, rerun the relevant Web batch artifacts before
updating paper latency or accuracy claims.
