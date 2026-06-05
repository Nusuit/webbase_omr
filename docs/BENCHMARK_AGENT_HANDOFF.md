# Benchmark Agent Handoff

> **SUPERSEDED (2026-05-30).** Historical handoff. The `96.4%` accuracy advice
> below is obsolete: the manuscript now uses `99.00%` (`9,393/9,488`) with a real
> human-reviewed ground truth in `runs/accuracy_eval/`. Do not reintroduce
> `96.4%`. Current source of truth is `omr_etc2026_v8_4_revised.tex` and
> `CHECKLIST.md`. Kept for history only; safe to delete.

Date: 2026-05-27

This note records the current benchmark state for the OMR paper and the exact work remaining per platform agent.

## Naming rule

- Paper-facing label: `Desktop reference`
- Raw artifact id already in `runs/`: `PC_gaming`
- Do not rename the existing `PC_gaming` files unless the whole results table is regenerated. In the manuscript, expose only `Desktop reference`; in scripts and raw paths, keep `PC_gaming`.

## What was wrong before

The desktop run itself is not the main problem. `PC_gaming` has already been run once and currently has usable `N=179` Web artifacts for both pipelines:

```text
runs/batch_results/PC_gaming_cv_n179.json
runs/batch_results/PC_gaming_yolo_n179.json
runs/resources/PC_gaming/resources_PC_gaming_cv_n179.csv
runs/resources/PC_gaming/resources_PC_gaming_cv_n179_sys.csv
runs/resources/PC_gaming/resources_PC_gaming_yolo_n179.csv
runs/resources/PC_gaming/resources_PC_gaming_yolo_n179_sys.csv
```

The earlier paper-level problem was interpretation and consistency:

- Web batch latency is `cpp_ms` measured inside the Web Worker, not full browser E2E. Fetch/decode/main-thread overhead is excluded.
- Native Android latency rows are currently from an old `N=56` file-based reference run, while Web rows are `N=179`.
- Native Android raw result JSON files for the refreshed `N=179` run are missing from `runs/batch_results/`.
- Stage-decomposition values use smaller profiles (`N=54` desktop, `N=43/50` mobile), so they must be labelled as stage profiles, not mixed as `N=179` deployment-set results.
- YOLO diagnostics conflict across hosts. Example: desktop/asus-style YOLO stats show 12 marker failures, while Redmi mobile YOLO shows 14. Do not claim platform-independent YOLO diagnostics unless backed by a ground-truth comparison.
- The `96.4%` accuracy/confusion claim is not backed by a clean artifact in `runs/` yet. If retained, create an accuracy evaluation file with ground truth, TP/FP/FN/TN, and exact-match accuracy.

## Current trusted Web numbers

Use these only with the boundary label `worker cpp_ms` or equivalent.

```text
Desktop reference / PC_gaming
  CV   248 +/- 15 ms, N=179
  YOLO 246 +/- 18 ms, N=179
  CPU/RAM sys CV   avg 60.5%, peak 168.7%, RAM avg 824 MB, peak 1310 MB
  CPU/RAM sys YOLO avg 55.7%, peak  76.5%, RAM avg 820 MB, peak  984 MB

Mobile Web / Redmi Note 13 Pro+
  CV   808 +/- 234 ms, N=179
  YOLO 729 +/- 109 ms, N=179
  Resource metrics are in-page proxy metrics, not psutil process RSS.
```

## Desktop reference agent

Status: no rerun required for the current paper unless a fresh final audit is requested.

If rerun is required, run from repo root:

```powershell
python scripts/run_platform.py PC_gaming
python scripts/plot_resources.py runs/resources/PC_gaming/
```

Expected outputs:

```text
runs/batch_results/PC_gaming_cv_n179.json
runs/batch_results/PC_gaming_yolo_n179.json
runs/resources/PC_gaming/resources_PC_gaming_cv_n179.csv
runs/resources/PC_gaming/resources_PC_gaming_cv_n179_sys.csv
runs/resources/PC_gaming/resources_PC_gaming_yolo_n179.csv
runs/resources/PC_gaming/resources_PC_gaming_yolo_n179_sys.csv
```

Manuscript label must remain `Desktop reference`, not `PC_gaming`.

## Redmi Mobile Web agent

Status: current `N=179` Web run exists, but rerun if the final table must be regenerated from a single fresh session.

Start server on laptop:

```powershell
python web/server.py 8080 --dataset=Dataset_OMR_classified
```

Connect phone Chrome through ADB:

```powershell
adb reverse tcp:8080 tcp:8080
adb forward tcp:9222 localabstract:chrome_devtools_remote
```

Run both pipelines:

```powershell
python scripts/drive_batch.py redmi_note13_pro_plus cv
python scripts/drive_batch.py redmi_note13_pro_plus yolo
python scripts/plot_resources.py runs/resources/redmi_note13_pro_plus/
```

Expected outputs:

```text
runs/batch_results/redmi_note13_pro_plus_cv_n179.json
runs/batch_results/redmi_note13_pro_plus_yolo_n179.json
runs/resources/redmi_note13_pro_plus/resources_redmi_note13_pro_plus_cv_n179.csv
runs/resources/redmi_note13_pro_plus/resources_redmi_note13_pro_plus_yolo_n179.csv
```

## Native Android agent

Status: highest priority. The paper currently lacks refreshed raw Native `N=179` latency JSON files.

Before running, verify the real package/activity. Existing source uses package `com.gradesnap.omr`, while older notes mention `com.kien.omr`; do not assume the old activity string is correct.

Required output files:

```text
runs/batch_results/native_results_cv_n179.json
runs/batch_results/native_results_yolo_cpu1.json
runs/batch_results/native_results_yolo_cpu4.json
runs/batch_results/native_results_yolo_nnapi.json
runs/resources/redmi_note13_pro_plus_native/resources_redmi_note13_pro_plus_native_cv_n179.csv
runs/resources/redmi_note13_pro_plus_native/resources_redmi_note13_pro_plus_native_yolo_n179.csv
```

After native runs finish:

```powershell
bash scripts/pull_native_resources.sh runs/resources/redmi_note13_pro_plus_native
python scripts/plot_resources.py runs/resources/redmi_note13_pro_plus_native/
```

Only after these four JSON files exist should the paper replace the legacy `N=56` Native rows with `N=179` values.

## Accuracy/ground-truth agent

Status: required if the manuscript keeps the `96.4%` accuracy claim.

Create a reproducible artifact under:

```text
runs/accuracy_eval/
```

Minimum required files:

```text
runs/accuracy_eval/accuracy_eval_n100_or_n179.json
runs/accuracy_eval/confusion_matrix.csv
```

Minimum fields:

```text
sheet_count
question_count
pipeline: cv | yolo
TP, FP, FN, TN
question_exact_match_accuracy
sheet_perfect_rate
invalid_key_count
invalid_student_id_count
ground_truth_source
```

Do not describe YOLO as "more conservative on faint marks" unless the evidence is question-level bubble comparison. YOLO only localizes regions; downstream bubble decisions are made by the OMR stage.

## Post-run aggregation

Generate diagnostics with explicit output names so one platform does not overwrite another:

```powershell
python scripts/aggregate_diag.py runs/batch_results/PC_gaming_cv_n179.json --out-diag runs/_diag_desktop_reference_cv.json --out-stats runs/_stats_desktop_reference_cv.json
python scripts/aggregate_diag.py runs/batch_results/PC_gaming_yolo_n179.json --out-diag runs/_diag_desktop_reference_yolo.json --out-stats runs/_stats_desktop_reference_yolo.json
python scripts/aggregate_diag.py runs/batch_results/redmi_note13_pro_plus_cv_n179.json --out-diag runs/_diag_redmi_cv.json --out-stats runs/_stats_redmi_cv.json
python scripts/aggregate_diag.py runs/batch_results/redmi_note13_pro_plus_yolo_n179.json --out-diag runs/_diag_redmi_yolo.json --out-stats runs/_stats_redmi_yolo.json
```

## Manuscript rules

- Current manuscript file: `omr_etc2026_v8_4_revised.tex`.
- Use `Desktop reference` in paper text/tables.
- Use `PC_gaming` only in artifact paths and internal run ids.
- Label Web latency as `worker cpp_ms`, not full E2E.
- Label Native values as legacy `N=56` until refreshed `N=179` JSON exists.
- Keep resource instruments separate: `psutil` for Windows Chrome, in-page proxy for Mac/iPhone/Android Chrome, `adb top` for Native Android.
