# OMR review pre-label

Method used for pre-labeling: `yolo`

Sheets processed: 179

YOLO preprocessing config:

- `pad`: `0.12`
- `mask`: `raw`
- `fallback`: `none`


This directory is for human verification. It is not a ground-truth artifact
until the CSV rows have been checked and corrected by a human.

Files:

- `index.html`: visual review page.
- `previews/*.jpg`: one annotated preview per sheet.
- `ground_truth_review_template.csv`: editable review CSV.
- `review_rows.jsonl`: same rows as JSON Lines for scripts.

How to verify:

1. Open `index.html`.
2. For each sheet, inspect the green selected bubbles and the answer text.
3. In `ground_truth_review_template.csv`, set `verified=1` when the pre-filled
   `gt_q01..gt_q60` answers are correct.
4. If a sheet is wrong, edit the affected `gt_qXX` cells and set `needs_fix=1`
   or add a note.
5. Leave `auto_qXX` unchanged; those are the model/pipeline predictions.

Columns:

- `verified`: user-owned. Put `1` after checking the sheet.
- `needs_fix`: user-owned. Put `1` if you changed any `gt_qXX` cell.
- `expected_questions`: valid question count for this dataset. Questions beyond
  this number should use `NA` in `gt_qXX` and are excluded from accuracy
  denominators.
- `review_priority` / `auto_flags`: generated hints for which sheets deserve
  extra attention first. They are not correctness labels.

Answer cell convention:

- blank = unanswered
- `A`, `B`, `C`, `D`, `E` = one selected option
- `A+B` style = multi-mark

Next step after verification:

- Save the reviewed CSV as the ground-truth source for the accuracy evaluator.
