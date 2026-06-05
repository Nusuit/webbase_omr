# OMR accuracy evaluation

Generated: `2026-05-28T04:01:13+00:00`

Ground truth source:

- `runs\accuracy_eval\review_cv_n179\ground_truth_review_template.csv`

Prediction sources:

- `Traditional CV`: `runs\accuracy_eval\review_cv_n179\ground_truth_review_template.csv`
- `YOLO mask12`: `runs\accuracy_eval\review_yolo_n179\ground_truth_review_template.csv`
- `YOLO pad20 mask`: `runs\accuracy_eval\review_yolo_pad20_mask_n179\ground_truth_review_template.csv`
- `YOLO raw`: `runs\accuracy_eval\review_yolo_raw_n179\ground_truth_review_template.csv`
- `YOLO pad20 bestdiag`: `runs\accuracy_eval\review_yolo_pad20_bestdiag_n179\ground_truth_review_template.csv`

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
| YOLO mask12 | 9,488 | 6,044 | 63.70% | 84/179 | 6,144 | 2,588 | 3,302 | 35,406 | 0.704 | 0.650 | 0.676 |
| YOLO pad20 mask | 9,488 | 7,059 | 74.40% | 101/179 | 7,156 | 1,917 | 2,290 | 36,077 | 0.789 | 0.758 | 0.773 |
| YOLO raw | 9,488 | 9,393 | 99.00% | 137/179 | 9,387 | 77 | 59 | 37,917 | 0.992 | 0.994 | 0.993 |
| YOLO pad20 bestdiag | 9,488 | 9,392 | 98.99% | 136/179 | 9,387 | 77 | 59 | 37,917 | 0.992 | 0.994 | 0.993 |
