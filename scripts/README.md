# Scripts Directory

This folder contains benchmark drivers, evaluation scripts, and plotting tools
for the OMR paper. Scripts here are not all interchangeable; use the categories
below before running or modifying anything.

## Benchmark Drivers

- `drive_batch.py`: main Chrome DevTools Protocol driver for Web CV/YOLO batch
  runs. Used for Windows and Android Chrome through ADB forwarding.
- `drive_batch_mac.py`: Mac/Safari-oriented batch driver.
- `drive_mobile_batch.py`: older mobile batch helper kept for compatibility.
- `run_platform.py`: higher-level platform runner wrapper.
- `run_acer_nitro5.ps1`: platform-specific Windows run wrapper.

New benchmark runs should be recorded in `runs/RUN_LOG.md` with command, device,
browser/runtime version, and output paths.

## Accuracy and Review

- `evaluate_accuracy_from_review.py`: computes accuracy from reviewed ground
  truth and prediction artifacts.
- `generate_review_previews.py`: creates review previews for human GT checking.
- `review_ground_truth_server.py`: local review server.
- `summarize_yolo_detection.py`: aggregates YOLO detection/handoff fields from
  a batch-result JSON.
- `aggregate_diag.py`: aggregates diagnostic counts from per-sheet data.

## Plotting and Tables

- `plot_latency_figure.py`: latency figure source.
- `plot_cross_platform.py`: cross-platform CPU/RAM figure source.
- `plot_per_sheet.py`: per-sheet resource traces.
- `plot_resources.py`: resource plots from CSVs.
- `_plot_utils.py`: shared plotting/resource helpers.
- `_compare_platforms.py`: ad hoc resource/platform comparison helper.

## Model Training and Native Helpers

- `train_yolov8.py`: YOLO training entry point.
- `paper_detection_reference.py`: reference/detection helper code.
- `build_opencv_variants.sh`: OpenCV/WASM variant build helper.
- `pull_native_resources.sh`: Native Android resource pull helper.

Before editing a script, check whether its outputs are already used by
`docs/ARTIFACT_MANIFEST_5PAGE.md` or the manuscript.
