# Web Runtime

This folder contains the browser-side OMR application used for the Web Edge
benchmark.

## Main Entry Points

- `batch-detect.html`: batch benchmark page used by `scripts/drive_batch.py`.
- `index.html`: interactive app entry point.
- `preview-print.html`: preview/printing workflow.
- `review-prelabel.html`: review helper for ground-truth work.
- `server.py`: local server with the cross-origin isolation headers required
  by SIMD/threads/WebAssembly paths.

## Subfolders

- `js/`: browser orchestration, workers, WASM bridge, and ONNX Runtime Web glue.
- `wasm/`: generated WebAssembly artifacts.
- `models/`: browser-loaded model artifacts.

## Benchmark Rule

For paper benchmarks, use `batch-detect.html` through the scripted drivers
rather than the interactive UI. Record new output paths in `runs/RUN_LOG.md`.
