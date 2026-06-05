# Artifact Manifest for `omr_etc2026_v8_4_revised.tex`

Last updated: 2026-05-29

This manifest records the current source of truth for the 5-page manuscript.
It separates artifacts that are directly reproducible from profile-derived
evidence that should be rerun if the paper is hardened further.

## Recognition Accuracy

| Claim/Table | Primary artifacts | Notes |
|---|---|---|
| CV and raw-handoff 99.00% question accuracy | `runs/accuracy_eval/accuracy_eval_n179.json`; `runs/accuracy_eval/accuracy_summary.csv`; `runs/accuracy_eval/question_level_predictions.csv`; `runs/accuracy_eval/sheet_level_metrics.csv` | Human-reviewed GT is `runs/accuracy_eval/review_cv_n179/ground_truth_review_template.csv`. |
| YOLO hard-mask 12% and 20% ablation | `runs/accuracy_eval/yolo_variant_eval/accuracy_eval_n179.json`; `runs/accuracy_eval/yolo_variant_eval/accuracy_summary.csv` | These rows evaluate detector-consuming variants, not raw handoff. |
| YOLO raw-handoff detection/handoff fields | `runs/accuracy_eval/yolo_detection_n179/summary.json`; `runs/accuracy_eval/yolo_detection_n179/per_sheet_detection.csv`; source JSON `runs/batch_results/redmi_note13_pro_plus_yolo_raw_n179.json` | Current summary: `154/179` have `yolo_detected=true`; `179/179` use raw handoff; fallback used is `0`. Do not claim `179/179` detector success from this artifact. |
| 2026-05-29 Redmi Web rerun detection/handoff fields | `runs/accuracy_eval/yolo_detection_20260529_n179/summary.json`; `runs/accuracy_eval/yolo_detection_20260529_n179/per_sheet_detection.csv`; source JSON `runs/batch_results/redmi_note13_pro_plus_20260529_yolo_raw_n179.json` | Rerun repeats the same detection/handoff result: `154/179` detected, `179/179` raw handoff, `0` fallback used. |

## Latency

| Claim/Table | Primary artifacts | Boundary |
|---|---|---|
| Web Edge latency rows | `runs/batch_results/PC_gaming_cv_n179.json`; `runs/batch_results/PC_gaming_yolo_n179.json`; `runs/batch_results/asus_vivobook_cv_n179.json`; `runs/batch_results/asus_vivobook_yolo_n179.json`; `runs/batch_results/acer_nitro5_cv_n179.json`; `runs/batch_results/acer_nitro5_yolo_n179.json`; `runs/batch_results/mac_air_m1_cv_n179.json`; `runs/batch_results/mac_air_m1_yolo_n179.json`; `runs/batch_results/iphone_16_cv_n179.json`; `runs/batch_results/iphone_16_yolo_n179.json`; `runs/batch_results/redmi_note13_pro_plus_cv_n179.json`; `runs/batch_results/redmi_note13_pro_plus_yolo_raw_n179.json` | Web rows report worker-side `cpp_ms`; Redmi raw-handoff also has `worker_total_ms` and `yolo_ms`. |
| 2026-05-29 Redmi Web rerun rows | `runs/batch_results/redmi_note13_pro_plus_20260529_cv_n179.json`; `runs/batch_results/redmi_note13_pro_plus_20260529_yolo_raw_n179.json` | CV rerun: `cpp_ms=795.2 +/- 118.7` ms. YOLO raw rerun: `cpp_ms=820.1 +/- 75.5` ms, `yolo_ms=309.4 +/- 39.0` ms, `worker_total_ms=1130.1 +/- 89.0` ms. Treat as a rerun artifact, not an automatic replacement for current table values. |
| Native Android latency rows | `runs/batch_results/native_results_cv_n179.json`; `runs/batch_results/native_results_yolo_cpu1_n179.json`; `runs/batch_results/native_results_yolo_cpu4_n179.json`; `runs/batch_results/native_results_yolo_nnapi_n179.json` | Native rows report file-based `e2e_ms`; per-sheet recognition export is not yet available. |
| Same-boundary Web-vs-Native ratios | Web `cpp_ms` from batch JSON; Native `cpp_ms` portion inside Native JSON files | Use only when text explicitly says same processing boundary. |
| Stage attribution rows | `tab:stage-mini` in the manuscript plus Native JSON files | The Redmi Web `N=43` row is an older stage-decomposition profile. Rerun on the 179-sheet artifact before treating it as same-artifact evidence. |

## Resource Usage

| Claim/Table/Figure | Primary artifacts | Instrument |
|---|---|---|
| Desktop/Vivobook/Nitro Windows resource rows | `runs/resources/PC_gaming/`; `runs/resources/legacy/asus_vivobook/`; `runs/resources/acer_nitro5/` | `psutil` renderer/process sampling and Web resource CSVs. |
| macOS/iOS/Android Web rows | `runs/resources/mac_air_m1/`; `runs/resources/iphone_16/`; `runs/resources/redmi_note13_pro_plus/` | Browser-exposed heap/WASM memory and in-page event-loop proxy. |
| 2026-05-29 Redmi Web rerun rows | `runs/resources/redmi_note13_pro_plus_20260529/` | Browser-exposed/in-page proxy only. CV resource summary: CPU proxy `67.64/178.59%`, RAM `46.94/156.42` MB mean/max. YOLO raw: CPU proxy `58.93/177.60%`, RAM `67.85/156.42` MB mean/max. `_sys.csv` files are not usable because desktop `psutil` cannot see Android Chrome renderer PIDs. |
| Native Android resource rows | `runs/resources/redmi_note13_pro_plus_native/`; raw pulls under `runs/_native_raw_3/Documents/` | Android in-app/native sampler. |
| Cross-platform resource figure | `figures/chart_cross_platform.pdf`; source script `scripts/plot_cross_platform.py` | Harmonized plotting view, not a direct CPU-efficiency comparison across instruments. |

## Current Device/Rerun Status

| Item | Status |
|---|---|
| ADB executable | Available at `C:/Users/Anh Kien/AppData/Local/Android/Sdk/platform-tools/adb.exe` and `C:/Android/Sdk/platform-tools/adb.exe`. |
| Connected Android device | Redmi Note 13 Pro+ connected on 2026-05-29 as `23090RA98G`; Chrome `148.0.7778.178`. |
| Completed Redmi Web reruns | CV and YOLO raw N=179 runs completed through ADB reverse/forward and Chrome CDP. See `docs/RERUN_SUMMARY_20260529.md`. |
| Native per-question export | Not blocked (correction 2026-05-30). Full buildable project is the sibling folder `C:/Kien/Mobile/orm/android` (gradlew + 31 Kotlin files). Per-question `result.answers` is already computed in `OmrProcessor` and used for `answered`/`multi`; exporting `auto_q01..auto_q60` in `HeadlessBenchmark.writeJson` plus a rebuild is the remaining task. |
