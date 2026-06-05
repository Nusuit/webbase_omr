"""
Cross-platform unified chart: 3 platforms overlaid on SAME metric.

Inputs (hard-coded paths):
  runs/resources/PC_gaming/resources_PC_gaming_cv_n179.csv
  runs/resources/redmi_note13_pro_plus/resources_redmi_note13_pro_plus_cv_n179.csv
  runs/resources/redmi_note13_pro_plus_native/resources_redmi_note13_pro_plus_native_cv_n179.csv

Output:
  runs/resources/chart_cross_platform.png
  runs/resources/chart_cross_platform.pdf

Dataset = 179 sheets across all platforms. This chart uses the Traditional
CV pipeline for all three traces and current PC_gaming/Redmi artifacts.

Two panels:
  (a) Process memory (MB) over time — 3 lines
       Web:    js_heap_used + wasm_heap_cv + wasm_heap_yolo  (process working memory)
       Native: RSS (resident set size from /proc/self/status — same definition `top` uses)
  (b) CPU utilisation (% of one core saturated) over time — 3 lines
       Web:    cpu_load_proxy × 100   (main-thread saturation; ceiling ≈100% since WASM single-thread)
       Native: cpu_percent            (top-style; can exceed 100% on multi-core)
"""

from pathlib import Path
import matplotlib.pyplot as plt
from _plot_utils import fnum, read_csv as read

PLATFORMS = [
    {
        "name":   "Desktop Web (Chrome WebGPU)",
        "csv":    "runs/resources/PC_gaming/resources_PC_gaming_cv_n179.csv",
        "schema": "web",
        "color":  "#3b82f6",
    },
    {
        "name":   "Mobile Web (Chrome Android WebGPU)",
        "csv":    "runs/resources/redmi_note13_pro_plus/resources_redmi_note13_pro_plus_cv_n179.csv",
        "schema": "web",
        "color":  "#10b981",
    },
    {
        "name":   "Native Android (CV, real /proc)",
        "csv":    "runs/resources/redmi_note13_pro_plus_native/resources_redmi_note13_pro_plus_native_cv_n179.csv",
        "schema": "android",
        "color":  "#ef4444",
    },
]


def extract(rows, schema):
    t = [fnum(r["t_ms"]) / 1000.0 for r in rows]   # seconds
    if schema == "web":
        # Process working memory approximation: JS heap used (main thread) +
        # WASM heap of both workers. This is the closest to RSS we can get
        # from `performance.memory` + worker queries.
        mem = [
            fnum(r["js_heap_used_mb"])
            + fnum(r["wasm_heap_cv_mb"])
            + fnum(r["wasm_heap_yolo_mb"])
            for r in rows
        ]
        # CPU saturation of the main thread, normalised to "% of 1 core".
        # cpu_load_proxy is (lag_over_interval); 1.0 means main thread was
        # 100% busy during the sample interval.
        cpu = [fnum(r["cpu_load_proxy"]) * 100.0 for r in rows]
    else:
        mem = [fnum(r["rss_mb"]) for r in rows]
        cpu = [fnum(r["cpu_percent"]) for r in rows]
    return t, mem, cpu


def stats(arr):
    if not arr: return (0.0, 0.0, 0.0, 0.0)
    n = len(arr); mean = sum(arr) / n
    var = sum((x - mean) ** 2 for x in arr) / (n - 1 if n > 1 else 1)
    return (min(arr), max(arr), mean, var ** 0.5)


def main():
    root = Path(__file__).resolve().parent.parent
    series = []
    for p in PLATFORMS:
        rows = read(root / p["csv"])
        t, mem, cpu = extract(rows, p["schema"])
        series.append({**p, "t": t, "mem": mem, "cpu": cpu, "n": len(rows)})

    fig, (axM, axC) = plt.subplots(2, 1, figsize=(13, 9), sharex=False)

    # ── Panel A: Memory ───────────────────────────────────────────────
    for s in series:
        axM.plot(s["t"], s["mem"], label=s["name"], color=s["color"], linewidth=1.6)
    axM.set_ylabel("Process memory (MB)")
    axM.set_title("Memory usage over time — CV pipeline, harmonised plotting units\n"
                  "Web: JS heap + WASM heap (both workers)    Native: RSS from /proc/self/status")
    axM.grid(True, alpha=0.3)

    # ── Panel B: CPU ──────────────────────────────────────────────────
    for s in series:
        axC.plot(s["t"], s["cpu"], label=s["name"], color=s["color"], linewidth=1.6)
    axC.axhline(100, color="#94a3b8", linestyle=":", linewidth=0.9,
                label="1 core fully saturated (100%)")
    axC.set_ylabel("CPU usage (% of one core — can exceed 100% on multi-core)")
    axC.set_xlabel("Time since benchmark start (seconds)")
    axC.set_title("Process CPU utilisation over time — CV pipeline, harmonised plotting units\n"
                  "Web: main-thread saturation × 100 (capped at ~100% — single-thread WASM)    "
                  "Native: top-style process CPU% (out of cores × 100)")
    handles, labels = axC.get_legend_handles_labels()
    fig.legend(
        handles,
        labels,
        loc="upper center",
        bbox_to_anchor=(0.5, 0.995),
        ncol=4,
        fontsize=8,
        framealpha=0.9,
        borderpad=0.25,
        handlelength=1.6,
        columnspacing=1.0,
    )
    axC.grid(True, alpha=0.3)

    # Summary box
    lines = ["Peak / mean   (179 sheets, 100 ms sampling)"]
    for s in series:
        mmin, mmax, mmean, msd = stats(s["mem"])
        cmin, cmax, cmean, csd = stats(s["cpu"])
        lines.append(
            f"{s['name'][:38]:<38}  mem peak {mmax:>4.0f}  mean {mmean:>4.0f}  "
            f"|  CPU peak {cmax:>4.0f}%  mean {cmean:>4.0f}%"
        )
    fig.text(0.01, 0.005, "\n".join(lines),
             family="monospace", fontsize=8, va="bottom", ha="left",
             bbox=dict(boxstyle="round,pad=0.5", fc="#f8fafc", ec="#94a3b8"))

    plt.tight_layout(rect=[0, 0.07, 1, 0.93])
    out = root / "runs" / "resources" / "chart_cross_platform.png"
    plt.savefig(out, dpi=130)
    plt.savefig(out.with_suffix(".pdf"))
    plt.close(fig)
    print(f"Wrote {out}")
    print(f"Wrote {out.with_suffix('.pdf')}")
    for s in series:
        mmin, mmax, mmean, _ = stats(s["mem"])
        cmin, cmax, cmean, _ = stats(s["cpu"])
        print(f"  {s['name']}:")
        print(f"    mem peak {mmax:.0f} MB  mean {mmean:.0f} MB")
        print(f"    cpu peak {cmax:.0f}%  mean {cmean:.0f}%")


if __name__ == "__main__":
    main()
