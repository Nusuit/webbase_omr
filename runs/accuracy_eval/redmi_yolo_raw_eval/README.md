# OMR accuracy evaluation

Generated: `2026-05-28T04:17:50+00:00`

Ground truth source:

- `runs\accuracy_eval\review_cv_n179\ground_truth_review_template.csv`

Prediction sources:

- `Traditional CV`: `runs\accuracy_eval\review_cv_n179\ground_truth_review_template.csv`
- `Mobile Web YOLO raw`: `runs\batch_results\redmi_note13_pro_plus_yolo_raw_n179.json`

Rules:

- `verified=1` rows only are accepted as ground truth.
- `NA` means the question is outside that dataset's valid range and is excluded.
- Blank ground truth means a valid unanswered question.
- Question accuracy is exact-match over the answer string.
- Bubble confusion expands each valid question into five A/B/C/D/E decisions.

Outputs:

- `accuracy_eval_n179.json`: full machine-readable summary.
- `accuracy_summary.csv`: one row per method.
- `confusion_matrix.csv`: bubble-level TP/FP/FN/TN by method.
- `accuracy_by_dataset.csv`: one row per method/dataset.
- `question_level_predictions.csv`: one row per method/sheet/question.
- `sheet_level_metrics.csv`: one row per method/sheet.

Summary:

| Method | Valid questions | Correct | Question acc. | Sheet exact | TP | FP | FN | TN | P | R | F1 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Traditional CV | 9,488 | 9,393 | 99.00% | 137/179 | 9,387 | 77 | 59 | 37,917 | 0.992 | 0.994 | 0.993 |
| Mobile Web YOLO raw | 9,488 | 9,393 | 99.00% | 137/179 | 9,387 | 77 | 59 | 37,917 | 0.992 | 0.994 | 0.993 |
