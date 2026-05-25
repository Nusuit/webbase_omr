"""Shared helpers for plot_resources.py / plot_per_sheet.py / plot_cross_platform.py.

Centralises CSV reading + per-sheet aggregation so each plotter only contains
its own layout logic.
"""
import csv
from collections import defaultdict
from pathlib import Path


def fnum(s, default=0.0):
    try:
        return float(s)
    except (ValueError, TypeError):
        return default


def read_csv(path):
    """Read a CSV (web or android schema) into a list of dicts."""
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def per_sheet_web(rows):
    """Aggregate web-schema rows by sheet_idx (0-based -> 1-based).

    Returns list of (sheet_idx_1based, peak_ram_mb, peak_cpu_pct).
    RAM = js_heap_used + wasm_heap_cv + wasm_heap_yolo (process working memory).
    CPU = cpu_load_proxy * 100 (main-thread saturation).
    """
    by_sheet = defaultdict(list)
    for r in rows:
        si = int(r["sheet_idx"])
        ram = (fnum(r["js_heap_used_mb"])
               + fnum(r["wasm_heap_cv_mb"])
               + fnum(r["wasm_heap_yolo_mb"]))
        cpu = fnum(r["cpu_load_proxy"]) * 100.0
        by_sheet[si + 1].append((ram, cpu))
    out = []
    for si in sorted(by_sheet):
        rams = [x[0] for x in by_sheet[si]]
        cpus = [x[1] for x in by_sheet[si]]
        out.append((si, max(rams), max(cpus)))
    return out


def per_sheet_native(rows, phase_filter):
    """Aggregate android-schema rows by sheet_idx, filtering by phase column.

    phase_filter: 'CV' for CV pipeline, 'NNAPI' for production YOLO provider.
    Returns list of (sheet_idx, peak_rss_mb, peak_cpu_pct).
    """
    by_sheet = defaultdict(list)
    for r in rows:
        if r["phase"] != phase_filter:
            continue
        si = int(r["sheet_idx"])
        if si <= 0:
            continue
        by_sheet[si].append((fnum(r["rss_mb"]), fnum(r["cpu_percent"])))
    out = []
    for si in sorted(by_sheet):
        rams = [x[0] for x in by_sheet[si]]
        cpus = [x[1] for x in by_sheet[si]]
        out.append((si, max(rams), max(cpus)))
    return out


REPO_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = REPO_ROOT / "runs" / "resources"
