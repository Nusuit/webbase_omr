# Experiment Run Log

This file records major benchmark/evaluation runs so old and new artifacts are
not confused.

## 2026-05-31 Native Android CV bubble-reader re-port (Rule E)

Purpose: close the recognition gap found on 2026-05-30 by re-porting the whole
C++ bubble reader (`src/core/omr_core.cpp`, "Rule E") into the Native Android
pipeline, instead of the piecemeal binarization swap that failed.

Code changes (Android project `C:/Kien/Mobile/orm/android`):

- `BubbleDetector.preprocess`: global Otsu → `adaptiveThreshold(MEAN_C, 31, 5)`.
- `BubbleDetector.circleDensity`: new circular-disk density sampler (radius 18 in
  1700×2400 warp space, single JNI bbox read per bubble).
- `OmrProcessor`: rewrote MSSV/KEY/answer reading as two passes — (1) sample every
  cell-centre density, estimate `global_avg_density` from bubbles >0.35; (2) decide.
  Questions use the per-row z-score gate (`density>0.15 AND density>row_mean+1σ`,
  row empty if max<0.15); MSSV/KEY pick the best digit if `density>0.20 AND
  ≥global_avg·0.40`. Replaces the rect white-ratio + fixed 15% threshold.

Validation: first simulated the full algorithm in Python/cv2 over the 179 staged
sheets (`/tmp/sim_omr_full.py`) — 60.1% → 79.7% vs Web — before touching Kotlin.

### Headline metric: Native CV vs reviewed GROUND TRUTH (NA excluded)

This is the correct accuracy metric, comparable to the paper's 99.00% Web number.
`scripts/compare_native_web.py <json> --gt-only`. Debug build, N=179:

| dataset | Native-vs-GT | Web-vs-GT | sheet-exact |
|---|---|---|---|
| dataset_1 | **99.6%** | 99.7% | 43/48 |
| dataset_2 | **99.8%** | 99.6% | 42/47 |
| dataset_3 | **99.9%** | 97.6% | 34/35 |
| dataset_4 | 63.1% | 99.6% | 19/45 |
| dataset_5 | 11.2% | 100%  | 0/4 |
| ALL | **87.1%** | 99.3% | 138/179 |

**On the well-lit datasets (dataset_1/2/3) Native CV reaches full parity with
Web/GT (99.6-99.9%).** Sheet exact-match 138/179 is on par with the paper's Web
137/179. The 87.1% overall is held down ENTIRELY by 25 dark-background sheets
(21 in dataset_4, all 4 in dataset_5) where corner detection picks the wrong
registration markers → wrong warp → whole sheet garbage. The 24 dataset_4 sheets
whose warp succeeds read at >90%, proving the bubble reader is correct; the
limit is localisation, not recognition.

CAUTION on the vs-Web metric: an earlier table in this session reported
"60.1% → 79.1% vs Web", which is misleading. It compared Native to the Web
*predictions* (not ground truth) and counted the `auto_q51..60` columns on
50-question datasets where `gt_q51..60 = NA`. Those columns are pipeline noise on
both sides, so vs-Web agreement there is meaningless. Always evaluate Native with
`--gt-only` (NA excluded), not the default vs-Web mode.

Other effects of the Rule E port: MSSV match → 153/179, exam → 154/179, dataset_4
average multi-marks/sheet 52.3 → 2.6 (the z-score gate removed over-detection),
CV timing cpp 74.6→82.5 ms (thresh 11.9→19.8 ms from adaptiveThreshold).
Artifact: `runs/batch_results/redmi_note13_pro_plus_native_cv_n179.json`.

Remaining gap (the only thing between here and ~99% Native parity):

- 25 dark-background sheets (dataset_4/5) where corner selection picks a wrong
  marker as TR/etc. Tried quadrant-farthest vs sum/diff extremes vs all-candidate
  pools — all give the SAME ~65% on dataset_4 (one variant lifted dataset_5 to 38%
  but hurt dataset_2/3). Likely needs the C++ marker detection matched exactly, or
  YOLO localisation. Separate from the bubble reader, which is correct.
- YOLO path unchanged (still uses `YoloPaperDetector` crop, not Web raw handoff).
- Native stays a latency/resource baseline until the dark-background localisation
  is fixed and the user finishes GT labelling.

## 2026-05-30 Redmi Note 13 Pro+ Native Android (first per-question export)

Purpose: first Native Android run that exports per-question predictions
(`auto_q01..auto_q60`) and same-boundary stage timers, so Native can be compared
against Web/GT for recognition, not only latency. Built from the sibling project
`C:/Kien/Mobile/orm/android`.

Code changes (Android project, not this repo):

- `SheetCapture`, `CvBenchmarkRunner`, `YoloBenchmarkRunner`,
  `HeadlessBenchmark.writeJson`: serialize `auto_qXX` (format `A`, `A+B`, ``) +
  stage timers (`bitmap_to_mat_ms`, `normalize_ms`, `thresh_ms`,
  `omr_detect_ms`, `yolo_prep_ms`, `yolo_post_ms`).
- `NormalizePaper.kt`: re-ported from the C++ reference `src/core/omr_warp.cpp`
  (`NormalizeSheet`). The old Kotlin port was stale (full-res detection,
  loose marker filter fill>=0.4/gray<160, no `CornersLookValid`) and fell back
  to a plain resize on real photos, breaking every OMR grid. New port adds the
  800px analysis downscale, tight marker filter (fill>=0.85, gray<80, area
  bounds, >=50%-of-largest grouping) and corner validation.

Environment: Redmi Note 13 Pro+ (`23090RA98G`), Android 15 / API 35, USB
debugging. **Debug build** (`assembleDebug`), images + manifest staged in the
app external dir `/sdcard/Android/data/com.gradesnap.omr/files/omr_bench_n179/`
because scoped storage blocks raw `File` reads of `/sdcard/Download`. Sheet order
matches the GT template via `runs/_native_stage_n179/mapping.csv`.

Outputs (`runs/batch_results/`):

- `redmi_note13_pro_plus_native_cv_n179.json`
- `redmi_note13_pro_plus_native_yolo_{cpu1,cpu4,nnapi}_n179.json`

Recognition (Native vs Web, `scripts/compare_native_web.py`) — after two
NormalizePaper fixes (re-port + `gray<130`):

- CV final state: dataset_1 82.6%, dataset_2 82.4%, dataset_3 72.6% per-question
  agreement; dataset_4 8.2%, dataset_5 4.6%. Overall 60.1% vs Web, MSSV 140/179,
  exam 149/179, `exact=0/179`.
- Two distinct divergences remain after the warp is fixed:
  1. Marker `mean_gray` filter was `<80` (C++ expects pure-black markers); Redmi
     dark-background photos expose markers at gray ~85-100, so all four corners
     were rejected and the warp fell back to misaligned paper corners. Relaxed to
     `<130` (sweep over 179: `<80` → 130/179 valid quads, `<130` → 174/179). This
     fixed the WARP on dataset_4/5 (e.g. sheet 166 now reads MSSV 111111 / exam
     002 correctly), lifting MSSV match 125→140 and exam 124→149.
  2. Bubble reading still diverges. Even with a correct warp, dataset_4/5 over-
     detect (~52 false multi-marks/sheet) and good sheets differ ~17-20% per
     question. Cause: `BubbleDetector` uses global Otsu + rectangular cell +
     fixed 15% fill threshold, while the C++ reference `src/core/omr_core.cpp`
     uses `adaptiveThreshold(MEAN_C,31,5)` + circular density sampling + per-sheet
     global density normalization as an integrated system. Swapping ONLY the
     binarization to adaptiveThreshold collapsed all datasets (dataset_1
     82.6%→1.0%), confirming the whole bubble-reader must be re-ported together,
     not piecemeal. Reverted to global Otsu.
- YOLO (cpu1): 6.5% overall — worse, all datasets. The Native YOLO runner uses
  `YoloPaperDetector.normalize` (crop/warp to the YOLO bbox) as the OMR
  normalizer, which is NOT the Web "raw handoff" (YOLO localizes, raw image then
  goes to the C++ OMR normalizer). To match Web, the Native YOLO path must hand
  the raw image to the CV normalize after YOLO localization.

Timing (debug build, file-based E2E, N=179) — preliminary, NOT canonical:

- Native YOLO E2E: CPU-1T 522+/-54 ms, CPU-4T 365+/-36 ms, NNAPI 264+/-20 ms
  (ORT 370 / 224 / 164 ms; provider order NNAPI < CPU-4T < CPU-1T).
- Native CV: cpp 74.6+/-9.5 ms, e2e 105.9+/-13.8 ms (decode 25, normalize 42,
  thresh 12, omr_detect 4).

Canonical status:

- Do NOT put these into the manuscript. Recognition is usable only on
  dataset_1/2/3 and still diverges from Web; dataset_4/5 need a dark-background
  marker fix. Timing is a debug build (release would change OMR/CV Kotlin time;
  ORT native time is unaffected). Recognition parity eval against the user's GT
  is blocked until labelling is complete (56/179 labelled as of 2026-05-30); the
  weak sheets are concentrated in dataset_4/5, so a dataset_1-3-only label set
  would overstate parity.

## 2026-05-29 Redmi Note 13 Pro+ Web Rerun

Purpose: rerun Mobile Web CV and YOLO raw handoff after USB debugging became
available.

Environment:

- Device: Redmi Note 13 Pro+ (`23090RA98G`)
- Browser: Android Chrome `148.0.7778.178`
- Server: `http://localhost:8080/batch-detect.html` through `adb reverse`
- Driver: `scripts/drive_batch.py` through Chrome DevTools Protocol on port
  `9222`

Outputs:

- CV JSON: `runs/batch_results/redmi_note13_pro_plus_20260529_cv_n179.json`
- YOLO raw JSON:
  `runs/batch_results/redmi_note13_pro_plus_20260529_yolo_raw_n179.json`
- Resource CSVs: `runs/resources/redmi_note13_pro_plus_20260529/`
- Detection summary: `runs/accuracy_eval/yolo_detection_20260529_n179/`
- Full summary: `docs/RERUN_SUMMARY_20260529.md`

Result:

- CV: `179/179`, `cpp_ms=795.2 +/- 118.7` ms.
- YOLO raw: `179/179`, `cpp_ms=820.1 +/- 75.5` ms,
  `yolo_ms=309.4 +/- 39.0` ms, `worker_total_ms=1130.1 +/- 89.0` ms.
- YOLO handoff audit: `154/179` detected, `179/179` raw handoff, `0` fallback.

Canonical status:

- This rerun is documented but does not automatically supersede the manuscript
  table values. The YOLO raw rerun is materially slower than the current paper
  artifact and needs session/thermal review before adoption.

## 2026-05-28 YOLO Raw-Handoff Detection Audit

Purpose: aggregate deployment fields from the existing Redmi YOLO raw-handoff
JSON.

Inputs:

- `runs/batch_results/redmi_note13_pro_plus_yolo_raw_n179.json`

Outputs:

- `runs/accuracy_eval/yolo_detection_n179/summary.json`
- `runs/accuracy_eval/yolo_detection_n179/per_sheet_detection.csv`

Result:

- `154/179` rows have `yolo_detected=true`.
- `179/179` rows use raw handoff.
- `0/179` rows use fallback.

Canonical status:

- This is the current audit artifact referenced by the manuscript wording.
  It supports raw-handoff completion, not `179/179` detector success.
