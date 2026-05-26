"""Aggregate per-sheet batch-detect results into the legacy _full_diag.json schema.

Input  : runs/batch_results/<platform>_<method>_n179.json  (list[dict])
Output : runs/_full_diag.json              (dict[dataset_key, list[entry]])
         runs/_dataset_bench_stats.json   (dict[dataset_key, summary])

Heuristic:
  marker_fail := (not keyValid) and (not mssvValid)

Each input entry contains: url, status, examCode, mssv, mssvValid, keyValid,
answered, suspicious, multi, cpp_ms.

Dataset key is extracted from the URL path /dataset/<key>/Bài làm/... → "dataset_1".

Usage:
  python scripts/aggregate_diag.py runs/batch_results/pc_cv_n179.json
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.parse
from collections import defaultdict


DATASET_RE = re.compile(r"/dataset/(dataset_\d+)/")


def extract_dataset_key(url: str) -> str | None:
    decoded = urllib.parse.unquote(url)
    m = DATASET_RE.search(decoded)
    return m.group(1) if m else None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", help="batch-results JSON from drive_batch.py")
    ap.add_argument("--out-diag", default="runs/_full_diag.json")
    ap.add_argument("--out-stats", default="runs/_dataset_bench_stats.json")
    args = ap.parse_args()

    with open(args.input, encoding="utf-8") as f:
        raw = json.load(f)

    by_ds = defaultdict(list)
    skipped = 0
    for entry in raw:
        url = entry.get("url", "")
        key = extract_dataset_key(url)
        if key is None:
            skipped += 1
            continue
        answered = int(entry.get("answered") or 0)
        suspicious = int(entry.get("suspicious") or 0)
        multi = int(entry.get("multi") or 0)
        key_valid = bool(entry.get("keyValid"))
        mssv_valid = bool(entry.get("mssvValid"))
        exam_code = entry.get("examCode") or ""
        # legacy schema stored keyDigits as a list of 3 ints
        key_digits = [int(c) for c in exam_code if c.isdigit()] if exam_code else []
        if len(key_digits) < 3:
            key_digits = (key_digits + [0, 0, 0])[:3]

        marker_fail = (not key_valid) and (not mssv_valid)
        by_ds[key].append({
            "idx": len(by_ds[key]) + 1,
            "answered": answered,
            "suspicious": suspicious,
            "multi": multi,
            "keyDigits": key_digits,
            "marker_fail": marker_fail,
        })

    diag = {k: by_ds[k] for k in sorted(by_ds.keys())}

    # Compute summary stats matching legacy _dataset_bench_stats.json shape
    stats = {}
    for key, lst in diag.items():
        n = len(lst)
        ans = [e["answered"] for e in lst]
        susp = [e["suspicious"] for e in lst]
        multi = [e["multi"] for e in lst]
        mf_count = sum(1 for e in lst if e["marker_fail"])
        full_60 = sum(1 for a in ans if a == 60)
        ge_50 = sum(1 for a in ans if a >= 50)
        lt_30 = sum(1 for a in ans if a < 30)
        stats[key] = {
            "n": n,
            "avg_answered": round(sum(ans) / n, 2) if n else 0,
            "min_answered": min(ans) if ans else 0,
            "max_answered": max(ans) if ans else 0,
            "full_60": full_60,
            "ge_50": ge_50,
            "lt_30": lt_30,
            "marker_fail": mf_count,
            "marker_fail_pct": round(100 * mf_count / n, 1) if n else 0,
            "avg_suspicious": round(sum(susp) / n, 2) if n else 0,
            "avg_multi": round(sum(multi) / n, 2) if n else 0,
            "total_multi": sum(multi),
        }

    os.makedirs(os.path.dirname(args.out_diag), exist_ok=True)
    with open(args.out_diag, "w", encoding="utf-8") as f:
        json.dump(diag, f, ensure_ascii=False, indent=2)
    with open(args.out_stats, "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)

    total = sum(s["n"] for s in stats.values())
    print(f"[OK] {len(diag)} datasets, total {total} sheets (skipped {skipped} unparsable URLs)")
    print(f"[OK] {args.out_diag}")
    print(f"[OK] {args.out_stats}")
    print()
    print(f"{'dataset':12} {'N':>4} {'avg_ans':>8} {'full60':>7} {'mfail':>10} {'avg_susp':>9} {'avg_multi':>10} {'tot_multi':>10}")
    for k, s in stats.items():
        print(f"{k:12} {s['n']:>4} {s['avg_answered']:>8.2f} {s['full_60']:>7} {s['marker_fail']:>3} ({s['marker_fail_pct']:>4.1f}%) {s['avg_suspicious']:>9.2f} {s['avg_multi']:>10.2f} {s['total_multi']:>10}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
