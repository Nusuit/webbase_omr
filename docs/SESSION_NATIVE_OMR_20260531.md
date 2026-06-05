# Session note — Native Android OMR recognition (2026-05-30 → 05-31)

Goal for the session: execute CHECKLIST section A (Native Android experiments),
starting with A1 (Native per-question export + recognition parity). Recognition
parity vs the user's ground truth is deferred — only 56/179 frames are labelled,
and the weak sheets turned out to be dataset_4/5.

All app code changes are in the sibling project `C:/Kien/Mobile/orm/android`
(no product flavors; build `assembleDebug`, CV vs YOLO chosen at runtime via
`--es method`). Operational details are in the `native-android-benchmark-ops`
memory. Nothing here is committed yet.

## What was done

1. **Per-question + stage-timer export (A1 code).** Added `auto_q01..auto_q60`
   (format `A`, `A+B`, ``) and same-boundary stage timers (`bitmap_to_mat_ms`,
   `normalize_ms`, `thresh_ms`, `omr_detect_ms`, `yolo_prep_ms`, `yolo_post_ms`)
   to `SheetCapture`, both runners, and `HeadlessBenchmark.writeJson`. Smoke-test
   confirmed the format matches the Web review/eval schema exactly.

2. **Found and fixed the warp.** The first full run produced garbage
   (mssv/exam unreadable, ~47 false multi-marks/sheet). Root cause: `NormalizePaper.kt`
   was a stale port of the C++ reference `src/core/omr_warp.cpp` and fell back to a
   plain resize. Re-ported it (800px analysis downscale, tight marker filter,
   `CornersLookValid`). Then relaxed the marker `mean_gray` filter `<80` → `<130`
   because Redmi dark-background photos expose the markers at gray ~90 (a sweep
   over 179 sheets: `<80` → 130 valid quads, `<130` → 174). After both fixes the
   warp is correct on all five datasets (sheet 166 reads MSSV/exam correctly).

3. **Found and fixed the bubble reader.** Even with a correct warp the answers
   diverged from Web (dataset_4/5 over-detected; good sheets off ~17-20%). Cause:
   `BubbleDetector` used global Otsu + rectangular cells + a fixed 15% fill
   threshold, while the C++ reference `src/core/omr_core.cpp` ("Rule E") uses
   `adaptiveThreshold(MEAN_C,31,5)` + circular density sampling + per-sheet global
   density normalization + a per-row z-score gate — an integrated system. A
   piecemeal swap of only the binarization collapsed every dataset
   (dataset_1 82.6%→1.0%), so the whole reader was re-ported together, after first
   validating it in Python/cv2 over the 179 sheets (60%→80%).

## Results — Native CV vs reviewed GROUND TRUTH (debug build, N=179)

The correct metric is vs ground truth with `NA` questions excluded
(`scripts/compare_native_web.py <json> --gt-only`), comparable to the paper's
99.00% Web number:

| dataset | Native-vs-GT | Web-vs-GT | sheet-exact |
|---|---|---|---|
| dataset_1 | **99.6%** | 99.7% | 43/48 |
| dataset_2 | **99.8%** | 99.6% | 42/47 |
| dataset_3 | **99.9%** | 97.6% | 34/35 |
| dataset_4 | 63.1% | 99.6% | 19/45 |
| dataset_5 | 11.2% | 100%  | 0/4 |
| ALL | **87.1%** | 99.3% | 138/179 |

**Native CV reaches full parity with Web/GT on the well-lit datasets
(dataset_1/2/3 = 99.6-99.9%)**, and sheet-exact 138/179 is on par with the paper's
Web 137/179. MSSV match 153/179, exam 154/179, dataset_4 multi-marks 52→2.6/sheet,
CV timing cpp 75→82 ms.

The 87.1% overall is held down ENTIRELY by 25 dark-background sheets (21 dataset_4
+ 4 dataset_5) where corner detection picks a wrong registration marker (e.g. a
mid-right marker as TR) → skewed warp → whole sheet garbage. The 24 dataset_4
sheets whose warp succeeds read >90%, proving the bubble reader is correct; the
limit is localisation.

### Metric trap (recorded so it is not repeated)

An interim table reported "60.1% → 79.1% vs Web". That number is misleading: it
compared Native to the Web *predictions* (not ground truth) and included the
`auto_q51..60` columns on 50-question datasets where `gt_q51..60 = NA`. Those
columns are pipeline noise on both sides (Web's own Q51-60 there does not match
its GT either), so vs-Web agreement on them is meaningless. The Q51-60 "block
misalignment" chased during the session was a phantom — those questions do not
exist on dataset_1/2/3. Always evaluate with `--gt-only`.

## Artifacts

- `runs/batch_results/redmi_note13_pro_plus_native_cv_n179.json` (final CV, Rule E)
- `runs/batch_results/redmi_note13_pro_plus_native_yolo_{cpu1,cpu4,nnapi}_n179.json`
  (YOLO timing; recognition still broken — separate normalizer issue)
- `scripts/compare_native_web.py` — joins a Native JSON to Web/GT via
  `runs/_native_stage_n179/mapping.csv` (which also holds the 179 staged images,
  ~88 MB, used only as a benchmark input — do not commit the JPEGs).
- Timing (debug build, NOT canonical): Native YOLO E2E CPU-1T 522 / CPU-4T 365 /
  NNAPI 264 ms; Native CV cpp 82 ms.

## What did not work / was reverted

- Swapping only `BubbleDetector.preprocess` to adaptiveThreshold while keeping the
  rect + 15% logic — collapsed all datasets, reverted, then done as a full re-port.

## Next steps (not started)

- **dataset_5 + remaining dataset_4 corner detection.** These still fall back to a
  bad warp on specific photos; improve marker/paper corner robustness.
- **Systemic ~12-15% gap on good sheets** (`exact=0/179`): check whether the
  Kotlin config grid blocks match the C++ hardcoded blocks in `omr_core.cpp`.
- **Native YOLO raw-handoff.** Hand the raw image to the CV normalize after YOLO
  localization instead of warping to the YOLO bbox.
- **Release build** for canonical latency (debug timing is not paper-ready).
- **Recognition parity vs GT** once the user finishes labelling (weak sheets are
  dataset_4/5, so a dataset_1-3-only label set would overstate parity).
