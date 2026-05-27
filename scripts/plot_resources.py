"""
Plot RAM and CPU time-series from Resource Monitor CSV exports.

Handles two CSV schemas automatically (detected from the header row):

  WEB schema (from web/js/app.js ResourceMonitor):
    t_ms, run_idx, sheet_idx,
    js_heap_used_mb, js_heap_total_mb, js_heap_limit_mb,
    wasm_heap_cv_mb, wasm_heap_yolo_mb,
    event_loop_lag_ms, cpu_load_proxy

  ANDROID schema (from ResourceMonitor.kt):
    t_ms, phase,
    jvm_heap_used_mb, jvm_heap_max_mb,
    native_heap_used_mb, native_heap_size_mb,
    rss_mb, cpu_percent

Usage:
  python scripts/plot_resources.py <csv_path> [<csv_path> ...]
  python scripts/plot_resources.py runs/resources/desktop/
  python scripts/plot_resources.py runs/resources/native/
"""

import sys
import csv
from pathlib import Path

try:
    import matplotlib.pyplot as plt
except ImportError:
    print("ERROR: matplotlib not installed. Run: pip install matplotlib")
    sys.exit(1)


def read_csv(path: Path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append(r)
    return rows, (reader.fieldnames or [])


def to_float(s, default=0.0):
    try:
        return float(s)
    except (ValueError, TypeError):
        return default


def to_int(s, default=0):
    try:
        return int(s)
    except (ValueError, TypeError):
        return default


def stats(arr):
    if not arr:
        return (0, 0, 0, 0)
    n = len(arr)
    mean = sum(arr) / n
    var = sum((x - mean) ** 2 for x in arr) / (n - 1 if n > 1 else 1)
    return (min(arr), max(arr), mean, var ** 0.5)


# ─── WEB plotter ─────────────────────────────────────────────────────────────

def plot_web(rows, out_path: Path):
    t = [to_float(r["t_ms"]) / 1000.0 for r in rows]
    js_used = [to_float(r["js_heap_used_mb"]) for r in rows]
    js_total = [to_float(r["js_heap_total_mb"]) for r in rows]
    wasm_cv = [to_float(r["wasm_heap_cv_mb"]) for r in rows]
    wasm_yolo = [to_float(r["wasm_heap_yolo_mb"]) for r in rows]
    lag = [to_float(r["event_loop_lag_ms"]) for r in rows]
    cpu = [to_float(r["cpu_load_proxy"]) for r in rows]

    boundaries_s = []
    prev_sheet, prev_run = None, None
    for r in rows:
        s = to_int(r.get("sheet_idx"))
        ru = to_int(r.get("run_idx"))
        if prev_sheet is not None and (s != prev_sheet or ru != prev_run):
            boundaries_s.append(to_float(r["t_ms"]) / 1000.0)
        prev_sheet, prev_run = s, ru

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(12, 8), sharex=True)
    ax1.plot(t, js_used, label="JS heap used (main)", color="#3b82f6", linewidth=1.5)
    ax1.plot(t, js_total, label="JS heap total (main)", color="#60a5fa",
             linewidth=1, linestyle="--", alpha=0.6)
    ax1.plot(t, wasm_cv, label="WASM heap (CV worker)", color="#10b981", linewidth=1.5)
    ax1.plot(t, wasm_yolo, label="WASM heap (YOLO worker)", color="#f59e0b", linewidth=1.5)
    ax1.set_ylabel("Memory (MB)")
    ax1.set_title(f"Memory over time — {out_path.stem}")
    ax1.legend(loc="upper left", fontsize=9)
    ax1.grid(True, alpha=0.3)
    for b in boundaries_s:
        ax1.axvline(b, color="#94a3b8", alpha=0.15, linewidth=0.5)

    ax2.plot(t, lag, label="Event-loop lag (ms)", color="#ef4444", linewidth=1.2)
    ax2.set_ylabel("Lag (ms)", color="#ef4444")
    ax2.tick_params(axis='y', labelcolor="#ef4444")
    ax2.grid(True, alpha=0.3)
    for b in boundaries_s:
        ax2.axvline(b, color="#94a3b8", alpha=0.15, linewidth=0.5)
    ax2b = ax2.twinx()
    ax2b.plot(t, cpu, label="CPU load proxy", color="#8b5cf6", linewidth=1.0, alpha=0.7)
    ax2b.set_ylabel("CPU load proxy (0=idle, 1+=saturated)", color="#8b5cf6")
    ax2b.tick_params(axis='y', labelcolor="#8b5cf6")
    ax2.set_xlabel("Time (seconds)")
    ax2.set_title("Event-loop lag (Web CPU pressure proxy)")

    js_min, js_max, js_mean, js_sd = stats(js_used)
    wcv_min, wcv_max, wcv_mean, wcv_sd = stats(wasm_cv)
    wyo_min, wyo_max, wyo_mean, wyo_sd = stats(wasm_yolo)
    lag_min, lag_max, lag_mean, lag_sd = stats(lag)
    cpu_min, cpu_max, cpu_mean, cpu_sd = stats(cpu)
    summary = (
        f"WEB schema — Samples: {len(rows)}  Duration: {t[-1]:.1f}s\n"
        f"JS heap used  — peak {js_max:.1f}  mean {js_mean:.1f} ± {js_sd:.1f} MB\n"
        f"WASM CV       — peak {wcv_max:.1f}  mean {wcv_mean:.1f} ± {wcv_sd:.1f} MB\n"
        f"WASM YOLO     — peak {wyo_max:.1f}  mean {wyo_mean:.1f} ± {wyo_sd:.1f} MB\n"
        f"Loop lag      — peak {lag_max:.0f}  mean {lag_mean:.1f} ± {lag_sd:.1f} ms\n"
        f"CPU proxy     — peak {cpu_max:.2f}  mean {cpu_mean:.2f} ± {cpu_sd:.2f}"
    )
    fig.text(0.99, 0.01, summary, ha="right", va="bottom",
             family="monospace", fontsize=8,
             bbox=dict(boxstyle="round,pad=0.5", fc="#f8fafc", ec="#94a3b8"))
    plt.tight_layout(rect=[0, 0.08, 1, 1])
    plt.savefig(out_path, dpi=120)
    plt.close(fig)
    return {
        "schema": "web",
        "samples": len(rows),
        "duration_s": t[-1],
        "wasm_cv_peak": wcv_max,
        "wasm_yolo_peak": wyo_max,
        "lag_peak": lag_max,
        "cpu_proxy_peak": cpu_max,
    }


# ─── ANDROID plotter ─────────────────────────────────────────────────────────

def plot_android(rows, out_path: Path):
    t = [to_float(r["t_ms"]) / 1000.0 for r in rows]
    jvm_used = [to_float(r["jvm_heap_used_mb"]) for r in rows]
    jvm_max = [to_float(r["jvm_heap_max_mb"]) for r in rows]
    nat_used = [to_float(r["native_heap_used_mb"]) for r in rows]
    nat_size = [to_float(r["native_heap_size_mb"]) for r in rows]
    rss = [to_float(r["rss_mb"]) for r in rows]
    cpu = [to_float(r["cpu_percent"]) for r in rows]

    # Phase boundaries — vertical lines + colored label bands.
    phases = [r.get("phase", "") for r in rows]
    phase_changes = []
    prev = None
    for i, p in enumerate(phases):
        if p != prev:
            phase_changes.append((t[i], p))
            prev = p

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(13, 8), sharex=True)

    # Panel 1: Memory
    ax1.plot(t, rss, label="Process RSS", color="#3b82f6", linewidth=1.8)
    ax1.plot(t, nat_used, label="Native heap used", color="#10b981", linewidth=1.4)
    ax1.plot(t, nat_size, label="Native heap reserved", color="#10b981",
             linewidth=1, linestyle="--", alpha=0.55)
    ax1.plot(t, jvm_used, label="JVM heap used", color="#f59e0b", linewidth=1.4)
    ax1.plot(t, jvm_max, label="JVM heap max", color="#f59e0b",
             linewidth=1, linestyle="--", alpha=0.55)
    ax1.set_ylabel("Memory (MB)")
    ax1.set_title(f"Native Android — Memory over time — {out_path.stem}")
    ax1.legend(loc="upper left", fontsize=9, ncol=2)
    ax1.grid(True, alpha=0.3)

    # Panel 2: CPU%
    ax2.plot(t, cpu, label="Process CPU%", color="#ef4444", linewidth=1.4)
    ax2.axhline(100, color="#94a3b8", linestyle=":", linewidth=0.8,
                label="1 core (100%)")
    ax2.set_ylabel("CPU%  (out of cores × 100)")
    ax2.set_xlabel("Time (seconds)")
    ax2.set_title("Native Android — Process CPU% (top-style)")
    ax2.legend(loc="upper left", fontsize=9)
    ax2.grid(True, alpha=0.3)

    # Mark phase transitions on both axes
    palette = {
        "init": "#cbd5e1", "idle": "#cbd5e1", "done": "#cbd5e1",
        "warmup": "#fde68a", "warmup_CV": "#fde68a",
        "CPU1T": "#a7f3d0", "CPU4T": "#bae6fd",
        "NNAPI": "#ddd6fe", "CV": "#fecaca",
    }
    phase_changes.append((t[-1], None))
    for i in range(len(phase_changes) - 1):
        x0, label = phase_changes[i]
        x1, _ = phase_changes[i + 1]
        color = palette.get(label, "#e5e7eb")
        for ax in (ax1, ax2):
            ax.axvspan(x0, x1, color=color, alpha=0.25)
        if label and (x1 - x0) > 0.5:
            ax1.text((x0 + x1) / 2, ax1.get_ylim()[1] * 0.96, label,
                     ha="center", va="top", fontsize=8, color="#334155",
                     family="monospace")

    rss_min, rss_max, rss_mean, rss_sd = stats(rss)
    nat_min, nat_max, nat_mean, nat_sd = stats(nat_used)
    jvm_min, jvm_max_v, jvm_mean, jvm_sd = stats(jvm_used)
    cpu_min, cpu_max, cpu_mean, cpu_sd = stats(cpu)

    summary = (
        f"NATIVE schema — Samples: {len(rows)}  Duration: {t[-1]:.1f}s\n"
        f"RSS         — peak {rss_max:.1f}  mean {rss_mean:.1f} ± {rss_sd:.1f} MB\n"
        f"Native heap — peak {nat_max:.1f}  mean {nat_mean:.1f} ± {nat_sd:.1f} MB\n"
        f"JVM heap    — peak {jvm_max_v:.1f}  mean {jvm_mean:.1f} ± {jvm_sd:.1f} MB\n"
        f"Process CPU — peak {cpu_max:.0f}%  mean {cpu_mean:.0f}% ± {cpu_sd:.0f}%"
    )
    fig.text(0.99, 0.01, summary, ha="right", va="bottom",
             family="monospace", fontsize=8,
             bbox=dict(boxstyle="round,pad=0.5", fc="#f8fafc", ec="#94a3b8"))

    plt.tight_layout(rect=[0, 0.09, 1, 1])
    plt.savefig(out_path, dpi=120)
    plt.close(fig)
    return {
        "schema": "android",
        "samples": len(rows),
        "duration_s": t[-1],
        "rss_peak": rss_max,
        "native_heap_peak": nat_max,
        "jvm_heap_peak": jvm_max_v,
        "cpu_peak": cpu_max,
    }


# ─── Dispatch ────────────────────────────────────────────────────────────────

def plot_csv(csv_path: Path):
    rows, fieldnames = read_csv(csv_path)
    if not rows:
        print(f"  [SKIP] {csv_path.name}: empty")
        return

    out_path = csv_path.parent / (csv_path.stem + ".png")
    if "cpu_percent" in fieldnames and "rss_mb" in fieldnames:
        meta = plot_android(rows, out_path)
        print(f"  [OK] {csv_path.name}  ->  {out_path.name}  (android schema)")
        print(f"       RSS peak={meta['rss_peak']:.0f} MB  native={meta['native_heap_peak']:.0f} MB  "
              f"CPU peak={meta['cpu_peak']:.0f}%")
    elif "cpu_load_proxy" in fieldnames or "wasm_heap_cv_mb" in fieldnames:
        meta = plot_web(rows, out_path)
        print(f"  [OK] {csv_path.name}  ->  {out_path.name}  (web schema)")
        print(f"       peak WASM CV={meta['wasm_cv_peak']:.0f} MB  "
              f"YOLO={meta['wasm_yolo_peak']:.0f} MB  lag peak={meta['lag_peak']:.0f}ms")
    else:
        print(f"  [SKIP] {csv_path.name}: unknown CSV schema "
              f"(headers: {fieldnames[:8]} …)")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    targets = []
    for arg in sys.argv[1:]:
        p = Path(arg)
        if p.is_dir():
            targets.extend(sorted(p.glob("*.csv")))
        elif p.is_file():
            targets.append(p)
        else:
            print(f"  [WARN] Not found: {arg}")

    if not targets:
        print("No CSV files to process.")
        sys.exit(1)

    for csv_path in targets:
        try:
            plot_csv(csv_path)
        except Exception as e:
            print(f"  [ERR] {csv_path.name}: {e}")

    print(f"Done. Processed {len(targets)} file(s).")


if __name__ == "__main__":
    main()
