# Batch Results

This folder contains per-sheet JSON outputs from Web and Native benchmark runs.

## Current Paper-Facing Files

The 5-page manuscript's canonical latency/evaluation source is recorded in
`docs/ARTIFACT_MANIFEST_5PAGE.md`. In this folder, the main paper-facing groups
are:

- Web rows: `PC_gaming_*`, `asus_vivobook_*`, `acer_nitro5_*`,
  `mac_air_m1_*`, `iphone_16_*`, `redmi_note13_pro_plus_*`.
- Native rows: `native_results_cv_n179.json` and
  `native_results_yolo_*_n179.json`.
- YOLO raw handoff: `redmi_note13_pro_plus_yolo_raw_n179.json`.

## 2026-05-29 Rerun Files

- `redmi_note13_pro_plus_20260529_cv_n179.json`
- `redmi_note13_pro_plus_20260529_yolo_raw_n179.json`

These are fresh Redmi Web reruns. They are valid artifacts, but they are not yet
the canonical paper table values because the YOLO raw run is materially slower
than the earlier canonical run. See `docs/RERUN_SUMMARY_20260529.md`.

## Legacy

`legacy/` contains older Vivobook/PC JSONs superseded by later named platform
runs. Keep them for audit comparison only.
