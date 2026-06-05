# OMR review pre-label

Method used for pre-labeling: `cv`

Sheets processed: 179

This directory is for human verification. It is not a ground-truth artifact
until the CSV rows have been checked and corrected by a human.

Files:

- `index.html`: visual review page.
- `previews/*.jpg`: one annotated preview per sheet.
- `ground_truth_review_template.csv`: editable review CSV.
- `review_rows.jsonl`: same rows as JSON Lines for scripts.

Recommended one-sheet review UI:

```powershell
python scripts\review_ground_truth_server.py --port 8092
```

Then open:

```text
http://127.0.0.1:8092/review
```

The UI shows one preview at a time, lets you edit `gt_q01..gt_q60`,
and writes directly back to `ground_truth_review_template.csv` when you
click `Accept`, `Save`, or `Save & Accept, Next`.

At the top of the UI, set `expected_questions` for each dataset before
reviewing that dataset. For example, if a dataset has 50 valid questions,
set it to `50` and click `Apply`; the UI writes `NA` to `gt_q51..gt_q60`
and disables those cells during review. The preview image is a static
pre-label artifact, so the UI overlays excluded question regions dynamically
instead of rewriting the preview JPG. `auto_qXX` predictions are preserved.

Manual CSV fallback:

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
- `expected_questions`: dataset-level valid question count. Questions beyond
  this count use `NA` in `gt_qXX` and should be excluded from accuracy
  denominators.
- `review_priority` / `auto_flags`: generated hints for which sheets deserve
  extra attention first. They are not correctness labels.
- `auto_qXX`: original prediction. Do not edit.
- `gt_qXX`: editable ground-truth answer after human verification.

Answer cell convention:

- blank = unanswered
- `A`, `B`, `C`, `D`, `E` = one selected option
- `A+B` style = multi-mark
- `NA` = question outside this dataset's valid range; exclude from accuracy

Next step after verification:

- Save the reviewed CSV as the ground-truth source for the accuracy evaluator.
