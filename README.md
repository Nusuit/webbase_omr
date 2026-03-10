# wasm-omr-mobile

Zero-server OMR demo for mobile browser:
- Camera stream via `getUserMedia`
- C++ OMR core compiled to Wasm via Emscripten
- Processing in Web Worker with versioned protocol
- Upload image mode with IndexedDB cache

## Current status

This module now has a migration-friendly architecture:
- `src/core/omr_core.*`: C++ core logic
- `src/core/omr_bindings.*`: C ABI exported to Wasm
- `web/js/worker-protocol.js`: message contract
- `web/js/worker.js`: worker orchestration
- `web/js/wasm-bridge.js`: JS <-> Wasm memory bridge

The algorithm is still a baseline binary-threshold + ROI black-count.
Next milestone is porting Android OMR pipeline into `src/core`.

## Build Wasm

```bash
cd wasm-omr-mobile
emcmake cmake -S . -B build
cmake --build build
```

Generated files:
- `web/wasm/omr.js`
- `web/wasm/omr.wasm`

## Run web app

```bash
cd wasm-omr-mobile/web
python -m http.server 8080
```

Open: `http://localhost:8080`

## Exported Wasm functions

- `_omr_process_frame`
- `_omr_process_frame_v2`
- `_malloc`
- `_free`

## Next porting targets from Android release

1. `NormalizePaper.kt` -> `src/core/normalize.*`
2. `BubbleDetector.kt` -> `src/core/bubble_detector.*`
3. `OmrProcessor.kt` orchestration -> `src/core/pipeline.*`
4. `ConfigLoader.kt` + JSON config parsing -> `src/core/layout_config.*`
