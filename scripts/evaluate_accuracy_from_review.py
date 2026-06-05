#!/usr/bin/env python3
"""Build reproducible OMR accuracy artifacts from reviewed ground truth.

Inputs are the human-reviewed CSV from `review_cv_n179` and one or more
prediction CSVs using the same `auto_qXX` columns. Questions whose ground-truth
cell is `NA` are outside that dataset's valid range and are excluded from all
accuracy denominators.
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote


REPO = Path(__file__).resolve().parent.parent
DEFAULT_GT = REPO / "runs" / "accuracy_eval" / "review_cv_n179" / "ground_truth_review_template.csv"
DEFAULT_CV = DEFAULT_GT
DEFAULT_YOLO = REPO / "runs" / "batch_results" / "redmi_note13_pro_plus_yolo_raw_n179.json"
DEFAULT_OUT = REPO / "runs" / "accuracy_eval"
CHOICES = ("A", "B", "C", "D", "E")


def normalize_answer(value: object) -> str:
    text = str(value or "").strip().upper()
    if text in {"N", "NA", "N/A"}:
        return "NA"
    parts = [part for part in text.split("+") if part]
    valid = sorted({part for part in parts if part in CHOICES}, key=CHOICES.index)
    return "+".join(valid)


def answer_set(value: str) -> set[str]:
    text = normalize_answer(value)
    if not text or text == "NA":
        return set()
    return set(text.split("+"))


def normalize_prediction_row(row: dict[str, object]) -> dict[str, str]:
    out = {str(key): "" if value is None else str(value) for key, value in row.items()}
    aliases = {
        "answered": "answered_auto",
        "suspicious": "suspicious_auto",
        "multi": "multi_auto",
        "status": "status_auto",
        "examCode": "exam_code_auto",
        "mssv": "mssv_auto",
    }
    for src, dst in aliases.items():
        if src in row and dst not in out:
            out[dst] = "" if row[src] is None else str(row[src])
    bool_aliases = {
        "mssvValid": "mssv_valid_auto",
        "keyValid": "key_valid_auto",
    }
    for src, dst in bool_aliases.items():
        if src in row and dst not in out:
            out[dst] = "1" if bool(row[src]) else "0"
    return out


def read_rows(path: Path) -> list[dict[str, str]]:
    if path.suffix.lower() == ".json":
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, list):
            raise ValueError(f"Expected JSON list in {path}")
        return [normalize_prediction_row(row) for row in data]
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return [dict(row) for row in csv.DictReader(handle)]


def sheet_id_from_url(url: str) -> str:
    decoded = unquote(str(url or ""))
    parts = decoded.replace("\\", "/").strip("/").split("/")
    if "dataset" in parts:
        idx = parts.index("dataset")
        if idx + 3 < len(parts):
            return f"{parts[idx + 1]}/{parts[-1]}"
    if "bench_dataset_root" in parts:
        idx = parts.index("bench_dataset_root")
        if idx + 2 < len(parts):
            return f"{parts[idx + 1]}/{parts[-1]}"
    return decoded


def row_sheet_id(row: dict[str, str]) -> str:
    if row.get("sheet_id"):
        return row["sheet_id"]
    return sheet_id_from_url(row.get("url", "") or row.get("source_url", ""))


def index_rows(rows: list[dict[str, str]], path: Path) -> dict[str, dict[str, str]]:
    indexed: dict[str, dict[str, str]] = {}
    duplicates = []
    for row in rows:
        sid = row_sheet_id(row)
        if sid in indexed:
            duplicates.append(sid)
        indexed[sid] = row
    if duplicates:
        raise ValueError(f"{path} has duplicate sheet_id values: {duplicates[:5]}")
    return indexed


def validate_ground_truth(gt_rows: list[dict[str, str]]) -> None:
    errors = []
    for row in gt_rows:
        sid = row_sheet_id(row)
        if row.get("verified") != "1":
            errors.append(f"{sid}: verified != 1")
        try:
            expected = int(row.get("expected_questions") or 60)
        except ValueError:
            errors.append(f"{sid}: expected_questions is not an integer")
            expected = 60
        if not 1 <= expected <= 60:
            errors.append(f"{sid}: expected_questions outside 1..60")
        for q in range(1, 61):
            gt = normalize_answer(row.get(f"gt_q{q:02d}", ""))
            if q > expected and gt != "NA":
                errors.append(f"{sid}: gt_q{q:02d} should be NA beyond expected={expected}")
            if q <= expected and gt == "NA":
                errors.append(f"{sid}: gt_q{q:02d} is NA inside expected={expected}")
    if errors:
        joined = "\n".join(errors[:50])
        raise ValueError(f"Ground-truth validation failed with {len(errors)} issue(s):\n{joined}")


def safe_float(value: object) -> float | None:
    try:
        return float(str(value))
    except (TypeError, ValueError):
        return None


def evaluate_method(
    method: str,
    pred_path: Path,
    gt_rows: list[dict[str, str]],
    gt_by_sheet: dict[str, dict[str, str]],
) -> tuple[dict[str, object], list[dict[str, object]], list[dict[str, object]], list[dict[str, object]]]:
    pred_rows = read_rows(pred_path)
    pred_by_sheet = index_rows(pred_rows, pred_path)
    missing = [row_sheet_id(row) for row in gt_rows if row_sheet_id(row) not in pred_by_sheet]
    extra = sorted(set(pred_by_sheet) - {row_sheet_id(row) for row in gt_rows})
    if missing or extra:
        raise ValueError(
            f"{method}: prediction sheet mismatch. missing={missing[:5]} extra={extra[:5]}"
        )

    question_rows: list[dict[str, object]] = []
    sheet_rows: list[dict[str, object]] = []
    confusion_rows: list[dict[str, object]] = []

    total_questions = correct_questions = 0
    sheet_exact = 0
    gt_blank = pred_blank = gt_multi = pred_multi = edited_cells = 0
    totals = Counter()
    by_dataset: dict[str, Counter] = defaultdict(Counter)
    numeric_fields = defaultdict(list)
    invalid_mssv = invalid_key = status_nonzero = 0

    for gt_row in gt_rows:
        sid = row_sheet_id(gt_row)
        dataset = gt_row.get("dataset", "")
        pred_row = pred_by_sheet[sid]
        expected = int(gt_row.get("expected_questions") or 60)
        sheet_total = sheet_correct = 0
        sheet_tp = sheet_fp = sheet_fn = sheet_tn = 0

        if pred_row.get("mssv_valid_auto") == "0":
            invalid_mssv += 1
        if pred_row.get("key_valid_auto") == "0":
            invalid_key += 1
        if str(pred_row.get("status_auto", "0")) not in {"", "0"}:
            status_nonzero += 1
        for field in ["answered_auto", "suspicious_auto", "multi_auto", "cpp_ms"]:
            value = safe_float(pred_row.get(field))
            if value is not None:
                numeric_fields[field].append(value)

        for q in range(1, 61):
            gt = normalize_answer(gt_row.get(f"gt_q{q:02d}", ""))
            pred = normalize_answer(pred_row.get(f"auto_q{q:02d}", ""))
            if gt == "NA":
                continue

            gt_choices = answer_set(gt)
            pred_choices = answer_set(pred)
            exact = gt == pred
            tp = len(gt_choices & pred_choices)
            fp = len(pred_choices - gt_choices)
            fn = len(gt_choices - pred_choices)
            tn = len(set(CHOICES) - (gt_choices | pred_choices))

            question_rows.append(
                {
                    "method": method,
                    "sheet_id": sid,
                    "dataset": dataset,
                    "question": q,
                    "expected_questions": expected,
                    "gt": gt,
                    "prediction": pred,
                    "exact_match": int(exact),
                    "tp": tp,
                    "fp": fp,
                    "fn": fn,
                    "tn": tn,
                }
            )

            total_questions += 1
            correct_questions += int(exact)
            sheet_total += 1
            sheet_correct += int(exact)
            sheet_tp += tp
            sheet_fp += fp
            sheet_fn += fn
            sheet_tn += tn
            totals.update({"tp": tp, "fp": fp, "fn": fn, "tn": tn})
            by_dataset[dataset].update(
                {
                    "questions": 1,
                    "correct": int(exact),
                    "tp": tp,
                    "fp": fp,
                    "fn": fn,
                    "tn": tn,
                }
            )
            if gt == "":
                gt_blank += 1
                by_dataset[dataset]["gt_blank"] += 1
            if pred == "":
                pred_blank += 1
                by_dataset[dataset]["pred_blank"] += 1
            if "+" in gt:
                gt_multi += 1
                by_dataset[dataset]["gt_multi"] += 1
            if "+" in pred:
                pred_multi += 1
                by_dataset[dataset]["pred_multi"] += 1
            if gt != pred:
                edited_cells += 1
                by_dataset[dataset]["mismatches"] += 1

        is_sheet_exact = int(sheet_total == sheet_correct)
        sheet_exact += is_sheet_exact
        sheet_rows.append(
            {
                "method": method,
                "sheet_id": sid,
                "dataset": dataset,
                "expected_questions": expected,
                "correct_questions": sheet_correct,
                "valid_questions": sheet_total,
                "question_accuracy": sheet_correct / sheet_total if sheet_total else 0.0,
                "sheet_exact_match": is_sheet_exact,
                "tp": sheet_tp,
                "fp": sheet_fp,
                "fn": sheet_fn,
                "tn": sheet_tn,
                "answered_auto": pred_row.get("answered_auto", ""),
                "suspicious_auto": pred_row.get("suspicious_auto", ""),
                "multi_auto": pred_row.get("multi_auto", ""),
                "status_auto": pred_row.get("status_auto", ""),
                "mssv_valid_auto": pred_row.get("mssv_valid_auto", ""),
                "key_valid_auto": pred_row.get("key_valid_auto", ""),
                "cpp_ms": pred_row.get("cpp_ms", ""),
            }
        )

    precision = totals["tp"] / (totals["tp"] + totals["fp"]) if totals["tp"] + totals["fp"] else 0.0
    recall = totals["tp"] / (totals["tp"] + totals["fn"]) if totals["tp"] + totals["fn"] else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0

    def mean(field: str) -> float | None:
        values = numeric_fields[field]
        return sum(values) / len(values) if values else None

    def dataset_summary(dataset: str, counter: Counter) -> dict[str, object]:
        p = counter["tp"] / (counter["tp"] + counter["fp"]) if counter["tp"] + counter["fp"] else 0.0
        r = counter["tp"] / (counter["tp"] + counter["fn"]) if counter["tp"] + counter["fn"] else 0.0
        f = 2 * p * r / (p + r) if p + r else 0.0
        return {
            "dataset": dataset,
            "valid_questions": counter["questions"],
            "correct_questions": counter["correct"],
            "question_accuracy": counter["correct"] / counter["questions"] if counter["questions"] else 0.0,
            "mismatches": counter["mismatches"],
            "gt_blank": counter["gt_blank"],
            "pred_blank": counter["pred_blank"],
            "gt_multi": counter["gt_multi"],
            "pred_multi": counter["pred_multi"],
            "tp": counter["tp"],
            "fp": counter["fp"],
            "fn": counter["fn"],
            "tn": counter["tn"],
            "precision": p,
            "recall": r,
            "f1": f,
        }

    summary: dict[str, object] = {
        "method": method,
        "prediction_csv": str(pred_path.relative_to(REPO) if pred_path.is_relative_to(REPO) else pred_path),
        "sheets": len(gt_rows),
        "valid_questions": total_questions,
        "correct_questions": correct_questions,
        "question_accuracy": correct_questions / total_questions if total_questions else 0.0,
        "sheet_exact_matches": sheet_exact,
        "sheet_exact_rate": sheet_exact / len(gt_rows) if gt_rows else 0.0,
        "gt_blank": gt_blank,
        "pred_blank": pred_blank,
        "gt_multi": gt_multi,
        "pred_multi": pred_multi,
        "question_mismatches": edited_cells,
        "invalid_mssv_count": invalid_mssv,
        "invalid_key_count": invalid_key,
        "status_nonzero_count": status_nonzero,
        "avg_answered_auto": mean("answered_auto"),
        "avg_suspicious_auto": mean("suspicious_auto"),
        "avg_multi_auto": mean("multi_auto"),
        "avg_cpp_ms": mean("cpp_ms"),
        "confusion": {
            "tp": totals["tp"],
            "fp": totals["fp"],
            "fn": totals["fn"],
            "tn": totals["tn"],
            "precision": precision,
            "recall": recall,
            "f1": f1,
        },
        "by_dataset": [
            dataset_summary(dataset, by_dataset[dataset])
            for dataset in sorted(by_dataset)
        ],
    }
    confusion_rows.append(
        {
            "method": method,
            "tp": totals["tp"],
            "fp": totals["fp"],
            "fn": totals["fn"],
            "tn": totals["tn"],
            "precision": precision,
            "recall": recall,
            "f1": f1,
        }
    )
    return summary, question_rows, sheet_rows, confusion_rows


def write_csv(path: Path, rows: list[dict[str, object]], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def fmt_pct(value: float) -> str:
    return f"{value * 100:.2f}%"


def write_readme(path: Path, payload: dict[str, object]) -> None:
    methods = payload["methods"]
    lines = [
        "# OMR accuracy evaluation",
        "",
        f"Generated: `{payload['generated_at']}`",
        "",
        "Ground truth source:",
        "",
        f"- `{payload['ground_truth_csv']}`",
        "",
        "Prediction sources:",
        "",
    ]
    for method in methods:
        lines.append(f"- `{method['method']}`: `{method['prediction_csv']}`")
    lines.extend([
        "",
        "Rules:",
        "",
        "- `verified=1` rows only are accepted as ground truth.",
        "- `NA` means the question is outside that dataset's valid range and is excluded.",
        "- Blank ground truth means a valid unanswered question.",
        "- Question accuracy is exact-match over the answer string.",
        "- Bubble confusion expands each valid question into five A/B/C/D/E decisions.",
        "",
        "Outputs:",
        "",
        "- `accuracy_eval_n179.json`: full machine-readable summary.",
        "- `accuracy_summary.csv`: one row per method.",
        "- `confusion_matrix.csv`: bubble-level TP/FP/FN/TN by method.",
        "- `accuracy_by_dataset.csv`: one row per method/dataset.",
        "- `question_level_predictions.csv`: one row per method/sheet/question.",
        "- `sheet_level_metrics.csv`: one row per method/sheet.",
        "",
        "Summary:",
        "",
        "| Method | Valid questions | Correct | Question acc. | Sheet exact | TP | FP | FN | TN | P | R | F1 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ])
    for method in methods:
        conf = method["confusion"]
        lines.append(
            "| {method} | {valid_questions:,} | {correct_questions:,} | {acc} | "
            "{sheet_exact_matches}/{sheets} | {tp:,} | {fp:,} | {fn:,} | {tn:,} | "
            "{p:.3f} | {r:.3f} | {f1:.3f} |".format(
                method=method["method"],
                valid_questions=method["valid_questions"],
                correct_questions=method["correct_questions"],
                acc=fmt_pct(method["question_accuracy"]),
                sheet_exact_matches=method["sheet_exact_matches"],
                sheets=method["sheets"],
                tp=conf["tp"],
                fp=conf["fp"],
                fn=conf["fn"],
                tn=conf["tn"],
                p=conf["precision"],
                r=conf["recall"],
                f1=conf["f1"],
            )
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ground-truth", default=str(DEFAULT_GT))
    parser.add_argument("--cv-predictions", default=str(DEFAULT_CV))
    parser.add_argument("--yolo-predictions", default=str(DEFAULT_YOLO))
    parser.add_argument(
        "--prediction",
        action="append",
        default=[],
        metavar="METHOD=CSV",
        help="Additional or replacement prediction source. If provided, defaults are not added.",
    )
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT))
    args = parser.parse_args()

    gt_path = Path(args.ground_truth).resolve()
    cv_path = Path(args.cv_predictions).resolve()
    yolo_path = Path(args.yolo_predictions).resolve()
    out_dir = Path(args.out_dir).resolve()

    gt_rows = read_rows(gt_path)
    validate_ground_truth(gt_rows)
    gt_by_sheet = index_rows(gt_rows, gt_path)

    if args.prediction:
        methods = []
        for spec in args.prediction:
            if "=" not in spec:
                raise SystemExit(f"--prediction must be METHOD=CSV, got: {spec}")
            name, path = spec.split("=", 1)
            methods.append((name.strip(), Path(path).resolve()))
    else:
        methods: list[tuple[str, Path]] = [("Traditional CV", cv_path)]
        if yolo_path.exists():
            methods.append(("YOLO + CV", yolo_path))

    summaries: list[dict[str, object]] = []
    question_rows: list[dict[str, object]] = []
    sheet_rows: list[dict[str, object]] = []
    confusion_rows: list[dict[str, object]] = []
    dataset_rows: list[dict[str, object]] = []
    for method, pred_path in methods:
        summary, q_rows, s_rows, c_rows = evaluate_method(method, pred_path, gt_rows, gt_by_sheet)
        summaries.append(summary)
        question_rows.extend(q_rows)
        sheet_rows.extend(s_rows)
        confusion_rows.extend(c_rows)
        for item in summary["by_dataset"]:
            row = {"method": method}
            row.update(item)
            dataset_rows.append(row)

    dataset_expected = defaultdict(Counter)
    for row in gt_rows:
        dataset_expected[row.get("dataset", "")][row.get("expected_questions", "")] += 1

    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "script": "scripts/evaluate_accuracy_from_review.py",
        "ground_truth_csv": str(gt_path.relative_to(REPO) if gt_path.is_relative_to(REPO) else gt_path),
        "dataset_expected_questions": {
            dataset: dict(counts) for dataset, counts in sorted(dataset_expected.items())
        },
        "methods": summaries,
    }

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "accuracy_eval_n179.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    summary_rows = []
    for item in summaries:
        conf = item["confusion"]
        summary_rows.append(
            {
                "method": item["method"],
                "sheets": item["sheets"],
                "valid_questions": item["valid_questions"],
                "correct_questions": item["correct_questions"],
                "question_accuracy": item["question_accuracy"],
                "sheet_exact_matches": item["sheet_exact_matches"],
                "sheet_exact_rate": item["sheet_exact_rate"],
                "gt_blank": item["gt_blank"],
                "pred_blank": item["pred_blank"],
                "gt_multi": item["gt_multi"],
                "pred_multi": item["pred_multi"],
                "question_mismatches": item["question_mismatches"],
                "invalid_mssv_count": item["invalid_mssv_count"],
                "invalid_key_count": item["invalid_key_count"],
                "status_nonzero_count": item["status_nonzero_count"],
                "avg_answered_auto": item["avg_answered_auto"],
                "avg_suspicious_auto": item["avg_suspicious_auto"],
                "avg_multi_auto": item["avg_multi_auto"],
                "avg_cpp_ms": item["avg_cpp_ms"],
                "tp": conf["tp"],
                "fp": conf["fp"],
                "fn": conf["fn"],
                "tn": conf["tn"],
                "precision": conf["precision"],
                "recall": conf["recall"],
                "f1": conf["f1"],
                "prediction_csv": item["prediction_csv"],
            }
        )
    write_csv(
        out_dir / "accuracy_summary.csv",
        summary_rows,
        [
            "method",
            "sheets",
            "valid_questions",
            "correct_questions",
            "question_accuracy",
            "sheet_exact_matches",
            "sheet_exact_rate",
            "gt_blank",
            "pred_blank",
            "gt_multi",
            "pred_multi",
            "question_mismatches",
            "invalid_mssv_count",
            "invalid_key_count",
            "status_nonzero_count",
            "avg_answered_auto",
            "avg_suspicious_auto",
            "avg_multi_auto",
            "avg_cpp_ms",
            "tp",
            "fp",
            "fn",
            "tn",
            "precision",
            "recall",
            "f1",
            "prediction_csv",
        ],
    )
    write_csv(
        out_dir / "confusion_matrix.csv",
        confusion_rows,
        ["method", "tp", "fp", "fn", "tn", "precision", "recall", "f1"],
    )
    write_csv(
        out_dir / "accuracy_by_dataset.csv",
        dataset_rows,
        [
            "method",
            "dataset",
            "valid_questions",
            "correct_questions",
            "question_accuracy",
            "mismatches",
            "gt_blank",
            "pred_blank",
            "gt_multi",
            "pred_multi",
            "tp",
            "fp",
            "fn",
            "tn",
            "precision",
            "recall",
            "f1",
        ],
    )
    write_csv(
        out_dir / "question_level_predictions.csv",
        question_rows,
        [
            "method",
            "sheet_id",
            "dataset",
            "question",
            "expected_questions",
            "gt",
            "prediction",
            "exact_match",
            "tp",
            "fp",
            "fn",
            "tn",
        ],
    )
    write_csv(
        out_dir / "sheet_level_metrics.csv",
        sheet_rows,
        [
            "method",
            "sheet_id",
            "dataset",
            "expected_questions",
            "correct_questions",
            "valid_questions",
            "question_accuracy",
            "sheet_exact_match",
            "tp",
            "fp",
            "fn",
            "tn",
            "answered_auto",
            "suspicious_auto",
            "multi_auto",
            "status_auto",
            "mssv_valid_auto",
            "key_valid_auto",
            "cpp_ms",
        ],
    )
    write_readme(out_dir / "README.md", payload)

    for row in summary_rows:
        print(
            "{method}: {correct}/{total} = {acc:.4%}, sheet_exact={sheet}/{sheets}, "
            "P={p:.4f} R={r:.4f} F1={f1:.4f}".format(
                method=row["method"],
                correct=row["correct_questions"],
                total=row["valid_questions"],
                acc=float(row["question_accuracy"]),
                sheet=row["sheet_exact_matches"],
                sheets=row["sheets"],
                p=float(row["precision"]),
                r=float(row["recall"]),
                f1=float(row["f1"]),
            )
        )
    print(f"[OK] wrote {out_dir / 'accuracy_eval_n179.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
