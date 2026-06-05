#!/usr/bin/env python3
"""Summarize YOLO detection/handoff fields from a batch-result JSON file."""
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
DEFAULT_INPUT = REPO / "runs" / "batch_results" / "redmi_note13_pro_plus_yolo_raw_n179.json"
DEFAULT_OUT = REPO / "runs" / "accuracy_eval" / "yolo_detection_n179"


def load_rows(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError(f"Expected a JSON list in {path}")
    return [row for row in data if isinstance(row, dict)]


def summarize(rows: list[dict], source: Path) -> dict:
    total = len(rows)
    detected = sum(1 for row in rows if row.get("yolo_detected") is True)
    fallback_used = sum(1 for row in rows if row.get("yolo_fallback_used") is True)
    fallback_none = sum(1 for row in rows if row.get("yolo_fallback") == "none")
    chosen_raw = sum(1 for row in rows if row.get("yolo_chosen_path") == "raw")
    chosen_path_counts = Counter(str(row.get("yolo_chosen_path", "")) for row in rows)
    fallback_counts = Counter(str(row.get("yolo_fallback", "")) for row in rows)
    mask_mode_counts = Counter(str(row.get("yolo_mask_mode", "")) for row in rows)

    misses = [
        {
            "index": idx + 1,
            "url": row.get("url", ""),
            "yolo_detected": row.get("yolo_detected"),
            "yolo_fallback": row.get("yolo_fallback"),
            "yolo_chosen_path": row.get("yolo_chosen_path"),
            "yolo_fallback_used": row.get("yolo_fallback_used"),
        }
        for idx, row in enumerate(rows)
        if row.get("yolo_detected") is not True
    ]

    return {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "source": str(source.relative_to(REPO)),
        "n": total,
        "yolo_detected_true": detected,
        "yolo_detected_false_or_missing": total - detected,
        "yolo_detected_rate": detected / total if total else 0.0,
        "yolo_chosen_path_raw": chosen_raw,
        "yolo_chosen_path_raw_rate": chosen_raw / total if total else 0.0,
        "yolo_fallback_none": fallback_none,
        "yolo_fallback_used_true": fallback_used,
        "chosen_path_counts": dict(chosen_path_counts),
        "fallback_counts": dict(fallback_counts),
        "mask_mode_counts": dict(mask_mode_counts),
        "miss_examples_first10": misses[:10],
        "miss_count": len(misses),
    }


def write_outputs(summary: dict, rows: list[dict], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    with (out_dir / "per_sheet_detection.csv").open("w", encoding="utf-8", newline="") as handle:
        fieldnames = [
            "index",
            "url",
            "yolo_detected",
            "yolo_mask_mode",
            "yolo_fallback",
            "yolo_chosen_path",
            "yolo_fallback_used",
            "yolo_ms",
            "cpp_ms",
            "worker_total_ms",
        ]
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for idx, row in enumerate(rows, start=1):
            writer.writerow({name: row.get(name, "") for name in fieldnames} | {"index": idx})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    input_path = args.input.resolve()
    out_dir = args.out_dir.resolve()

    rows = load_rows(input_path)
    summary = summarize(rows, input_path)
    write_outputs(summary, rows, out_dir)

    print(f"Wrote {out_dir / 'summary.json'}")
    print(f"Wrote {out_dir / 'per_sheet_detection.csv'}")
    print(
        "Detected {}/{} ({:.2%}); raw handoff {}/{}; fallback used {}.".format(
            summary["yolo_detected_true"],
            summary["n"],
            summary["yolo_detected_rate"],
            summary["yolo_chosen_path_raw"],
            summary["n"],
            summary["yolo_fallback_used_true"],
        )
    )


if __name__ == "__main__":
    main()
