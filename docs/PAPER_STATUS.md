# Paper status

Date: 2026-05-28, Asia/Saigon (page-count corrected 2026-05-30)

Note: the title and body below previously said "13-page". That was stale. The
current `omr_etc2026_v8_4_revised.tex` compiles to **5 pages** (target is ≤6).
See the corrected compile check at the end of this file.

Source of truth: `omr_etc2026_v8_4_revised.tex`.

Do not use `translations/*` or `experimental_results_discussion.tex` as metric sources unless they are explicitly refreshed against the current artifact set.

## Completed in this cleanup pass

- Accuracy artifact is now packaged under `runs/accuracy_eval/`.
  Source ground truth: `runs/accuracy_eval/review_cv_n179/ground_truth_review_template.csv`.
  Rebuild script: `scripts/evaluate_accuracy_from_review.py`.
  Primary outputs: `accuracy_eval_n179.json`, `accuracy_summary.csv`,
  `confusion_matrix.csv`, `accuracy_by_dataset.csv`,
  `question_level_predictions.csv`, and `sheet_level_metrics.csv`.
- Human-review pre-label artifacts were generated and fully verified under
  `runs/accuracy_eval/review_cv_n179/` (`verified=1` for 179/179 rows).
- Table `tab:diag` is scoped to Mobile Web (Redmi) CV diagnostics. Source: `runs/batch_results/redmi_note13_pro_plus_cv_n179.json`; script: `scripts/aggregate_diag.py`.
- Native Android diagnostics are not used as recognition-quality baselines. Native remains a latency/resource baseline until Web/Native predictions are compared against ground truth.
- Latency wording distinguishes Web `cpp_ms`, Native file-based `e2e_ms`, ORT `ort_ms`, stage-profile time, and missing Web browser wall-time.
- Welch/paired t-test wording is scoped to processing time excluding I/O, computed from per-sheet `cpp_ms` fields in `runs/batch_results/`.
- Resource scripts were moved to current non-archived artifact inputs:
  - `scripts/plot_per_sheet.py`
  - `scripts/plot_cross_platform.py`
- Resource figures were regenerated from current `runs/resources/` CSVs and copied to `figures/` as both PNG and PDF. The manuscript now includes the PDF chart files.
- Resource figure captions now state the instrument boundary: Web figure traces use in-page `cpu_load_proxy`, while Table `tab:resources` uses psutil for Windows hosts, proxy for Mac/iOS/Android Web, and Native sampler for Android.
- Strong wording was softened where it exceeded the artifact boundary, including platform-isolation wording, WebKit/Blink interpretation, Native resource-efficiency wording, and WebNN maturity wording.
- The WebNN bibliography entry was verified against the W3C current spec page and updated to Candidate Recommendation Draft, 21 May 2026.

## Current experiment status

Performance/resource experiments are usable for the current ≤6-page benchmark paper with the current cautious wording:

- Cross-platform latency: usable, but Web rows are worker compute time (`cpp_ms`) while Native rows are file-based E2E (`e2e_ms`).
- Stage decomposition: usable as stage-profile evidence, not as a deployment-set table.
- Resource usage: usable after the current artifact/script cleanup; table and figures now use current `PC_gaming`, Redmi Web, and Redmi Native resources.
- Recognition accuracy: usable for the reviewed `N=179` Web prediction
  artifacts. Traditional CV is 9,393/9,488 question exact-match
  (99.00%, sheet exact 137/179). Mobile Web YOLO raw-handoff rerun
  `runs/batch_results/redmi_note13_pro_plus_yolo_raw_n179.json` also
  reaches 9,393/9,488 (99.00%, sheet exact 137/179) and exports
  `auto_q01..auto_q60` directly from the Redmi batch.
- YOLO preprocessing debug: Web reruns show the low YOLO accuracy in the
  earlier export was caused by hard-masking outside the YOLO marker bbox
  before C++ OMR, not by the bubble classifier. `review_yolo_pad20_mask_n179`
  improves to 7,059/9,488 (74.40%) but remains poor; raw handoff passes
  the raw image to C++ after YOLO inference and matches CV at
  9,393/9,488 (99.00%). Summary artifact:
  `runs/accuracy_eval/yolo_variant_eval/`; fixed raw/no-mask artifact:
  `runs/accuracy_eval/yolo_fix_raw_eval/`.
- Review preview set: fully verified. It matches the Mobile Web CV
  diagnostic aggregate (`N=179`, avg answered 58.25, full-60 sheets 62,
  total multi-mark 100).
- Review CSV/UI now supports `expected_questions` per dataset. Setting a dataset
  to 50 or 51 valid questions writes `NA` to trailing `gt_qXX` cells so those
  questions can be excluded from accuracy denominators while preserving
  `auto_qXX` predictions for audit. The review UI overlays excluded regions on
  top of the static preview JPG; it does not rewrite the preview image itself.

## Needs user/data before final camera-ready claims

- Mobile YOLO exact-run prediction export: completed with USB/Chrome
  remote debugging on Redmi Note 13 Pro+. Command used:
  `python scripts\drive_mobile_batch.py yolo runs\resources\redmi_note13_pro_plus\resources_redmi_note13_pro_plus_yolo_raw_n179.csv --yolo-mask raw --out-json runs\batch_results\redmi_note13_pro_plus_yolo_raw_n179.json`.
  The batch completed 179/179 sheets in 215.4 s and produced 1,364
  browser resource samples.
- Web browser wall-time/E2E: collect it only if the paper wants a strict Web-vs-Native E2E latency comparison. The current benchmark correctly reports Web worker compute vs Native file-based E2E.
- Native diagnostic divergence: compare Native CV/YOLO predictions with ground truth before claiming recognition equivalence. Otherwise keep Native as latency/resource baseline only.
- YOLO deployment localisation: export per-sheet bbox/confidence for `N=179` before making any claim such as 100% deployment localisation.
- Device/browser manifest: before submission, confirm that the listed OS/browser versions and dataset order match the current artifacts.

## Last compile check

`pdflatex -interaction=nonstopmode -halt-on-error omr_etc2026_v8_4_revised.tex`
completed successfully (re-verified 2026-05-30, two passes).

- Output: `omr_etc2026_v8_4_revised.pdf`
- Page count: 5 pages (target ≤6)
- Undefined citations/references: 0
- Overfull hbox/vbox warnings: 0
- Remaining layout warnings: 7 underfull (re-verified 2026-05-30)

An attempted `microtype` cleanup was not kept because the local MiKTeX
installation does not include `microtype.sty`.
