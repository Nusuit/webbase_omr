#!/usr/bin/env python3
"""Compare a Native Android batch JSON against the Web predictions / reviewed GT.

The Native headless benchmark (HeadlessBenchmark.writeJson) exports per-sheet
`auto_q01..auto_q60`, `mssv`, `examCode`, plus stage timers. This script aligns
those rows to the reviewed ground-truth template
(`runs/accuracy_eval/review_cv_n179/ground_truth_review_template.csv`), which
already holds the Web pipeline predictions (`auto_qXX`, `mssv_auto`,
`exam_code_auto`) and the human ground truth (`gt_qXX`).

Join key: each Native row's `url` ends in a staged filename (NNN.jpg). The
staging mapping (`runs/_native_stage_n179/mapping.csv`) maps NNN -> sheet_id,
which keys the GT template. This avoids relying on row order.

Usage:
  python scripts/compare_native_web.py runs/batch_results/redmi_note13_pro_plus_native_cv_n179.json
  python scripts/compare_native_web.py <native.json> --gt-only   # vs reviewed GT instead of Web

Reports per-dataset and overall:
  - per-question agreement Native-vs-Web (or vs GT),
  - MSSV / examCode match,
  - sheet-exact agreement,
  - the worst sheets by agreement (likely warp/threshold divergence).

Note: GT columns may contain "NA" for datasets with <60 valid questions; those
cells are excluded from the GT denominator but kept for the Web comparison.
"""
import argparse
import csv
import json
import os
from collections import defaultdict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GT_TEMPLATE = os.path.join(
    REPO, "runs", "accuracy_eval", "review_cv_n179", "ground_truth_review_template.csv"
)
MAPPING = os.path.join(REPO, "runs", "_native_stage_n179", "mapping.csv")


def load_mapping():
    """staged filename (e.g. '166.jpg') -> sheet_id."""
    out = {}
    with open(MAPPING, encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            out[r["staged"]] = r["sheet_id"]
    return out


def load_gt():
    """sheet_id -> row dict from the reviewed template."""
    with open(GT_TEMPLATE, encoding="utf-8-sig") as fh:
        return {r["sheet_id"]: r for r in csv.DictReader(fh)}


def staged_from_url(url):
    return os.path.basename(url.replace("\\", "/"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("native_json")
    ap.add_argument("--gt-only", action="store_true",
                    help="Compare Native vs reviewed GT (gt_qXX) instead of Web (auto_qXX).")
    ap.add_argument("--worst", type=int, default=12)
    args = ap.parse_args()

    mapping = load_mapping()
    gt = load_gt()
    native = json.load(open(args.native_json, encoding="utf-8"))

    ref_prefix = "gt_q" if args.gt_only else "auto_q"
    label = "GT" if args.gt_only else "Web"

    agg = defaultdict(lambda: {"n": 0, "match": 0, "den": 0, "mssv": 0,
                               "exam": 0, "exact": 0})
    per_sheet = []
    unmatched = 0

    for row in native:
        staged = staged_from_url(row.get("url", ""))
        sheet_id = mapping.get(staged)
        g = gt.get(sheet_id) if sheet_id else None
        if g is None:
            unmatched += 1
            continue
        ds = sheet_id.split("/")[0]
        a = agg[ds]
        a["n"] += 1

        sheet_match = sheet_den = 0
        for q in range(1, 61):
            nv = row.get("auto_q%02d" % q, "")
            rv = g.get("%s%02d" % (ref_prefix, q), "")
            if args.gt_only and rv in ("", "NA"):
                continue  # excluded question
            sheet_den += 1
            if nv == rv:
                sheet_match += 1
        a["match"] += sheet_match
        a["den"] += sheet_den
        if sheet_den > 0 and sheet_match == sheet_den:
            a["exact"] += 1
        if str(row.get("mssv")) == str(g.get("mssv_auto")):
            a["mssv"] += 1
        if str(row.get("examCode")) == str(g.get("exam_code_auto")):
            a["exam"] += 1
        per_sheet.append((sheet_id, sheet_match, sheet_den,
                          row.get("multi", 0), row.get("suspicious", 0)))

    print("Native (%s) vs %s — %s" % (os.path.basename(args.native_json), label, args.native_json))
    if unmatched:
        print("  WARNING: %d native rows had no sheet_id match" % unmatched)
    print()
    print("%-10s %4s %10s %8s %8s %8s" % ("dataset", "n", "q-agree", "exact", "mssvOK", "examOK"))
    tot = defaultdict(int)
    for ds in sorted(agg):
        a = agg[ds]
        pct = 100 * a["match"] / a["den"] if a["den"] else 0
        print("%-10s %4d %9.1f%% %6d/%d %5d/%d %5d/%d" % (
            ds, a["n"], pct, a["exact"], a["n"], a["mssv"], a["n"], a["exam"], a["n"]))
        for k in ("n", "match", "den", "mssv", "exam", "exact"):
            tot[k] += a[k]
    pct = 100 * tot["match"] / tot["den"] if tot["den"] else 0
    print("%-10s %4d %9.1f%% %6d/%d %5d/%d %5d/%d" % (
        "ALL", tot["n"], pct, tot["exact"], tot["n"], tot["mssv"], tot["n"], tot["exam"], tot["n"]))

    print()
    per_sheet.sort(key=lambda r: (r[1] / r[2]) if r[2] else 0)
    print("Worst %d sheets (sheet_id, q-agree, multi, susp):" % args.worst)
    for sid, m, d, multi, susp in per_sheet[:args.worst]:
        print("  %-52s %2d/%d  multi=%s susp=%s" % (sid[:52], m, d, multi, susp))


if __name__ == "__main__":
    main()
