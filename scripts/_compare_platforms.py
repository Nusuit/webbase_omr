"""Print a cross-platform comparison table from runs/resources CSVs."""
import csv
import os
import statistics

ROOT = "runs/resources"
METHODS = ["cv", "yolo"]


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def is_native_schema(rows):
    return "jvm_heap_used_mb" in rows[0]


def summarize(rows, method):
    duration = max(float(r["t_ms"]) for r in rows) / 1000
    if is_native_schema(rows):
        # Android native schema
        ram_peak = max(float(r["rss_mb"]) for r in rows)
        cpu_mean = statistics.mean(float(r["cpu_percent"]) for r in rows)
        return dict(
            js_peak=max(float(r["jvm_heap_used_mb"]) for r in rows),
            wasm_peak=max(float(r["native_heap_used_mb"]) for r in rows),
            lag_peak=0, lag_p50=0, duration=duration, cpu_proxy=0,
            cpu_real=f"{cpu_mean:.0f}%", ram_peak=f"{ram_peak:.0f}",
        )
    # Web schema
    wasm_col = "wasm_heap_cv_mb" if method == "cv" else "wasm_heap_yolo_mb"
    js_peak   = max(float(r["js_heap_used_mb"]) for r in rows)
    wasm_peak = max(float(r[wasm_col]) for r in rows)
    lag_peak  = max(float(r["event_loop_lag_ms"]) for r in rows)
    lag_p50   = statistics.median(float(r["event_loop_lag_ms"]) for r in rows)
    cpu_proxy = statistics.mean(float(r["cpu_load_proxy"]) for r in rows) * 100
    return dict(
        js_peak=js_peak, wasm_peak=wasm_peak,
        lag_peak=lag_peak, lag_p50=lag_p50,
        duration=duration, cpu_proxy=cpu_proxy,
        cpu_real="n/a", ram_peak="n/a",
    )


for method in METHODS:
    print(f"\n{'='*80}")
    print(f"  {method.upper()} -- n=179 sheets")
    print(f"{'='*80}")
    cols = ["Platform", "Time(s)", "JS peak(MB)", "WASM peak(MB)", "Lag p50(ms)", "Lag peak(ms)", "CPU proxy%", "CPU real%*", "RAM peak(MB)*"]
    widths = [16, 9, 12, 13, 11, 13, 11, 11, 14]
    header = "  " + "  ".join(c.rjust(w) for c, w in zip(cols, widths))
    print(header)
    print("  " + "-" * (len(header) - 2))

    for plat in sorted(os.listdir(ROOT)):
        csv_path = os.path.join(ROOT, plat, f"resources_{plat}_{method}_n179.csv")
        if not os.path.exists(csv_path):
            continue
        rows = read_csv(csv_path)
        if not rows:
            continue
        s = summarize(rows, method)

        cpu_real_str = s["cpu_real"]
        ram_peak_str = s["ram_peak"]
        sys_path = os.path.join(ROOT, plat, f"resources_{plat}_{method}_n179_sys.csv")
        if os.path.exists(sys_path):
            sys_rows = read_csv(sys_path)
            if sys_rows:
                valid = [r for r in sys_rows if float(r["cpu_pct"]) > 0]
                if valid:
                    cpu_real_str = f"{statistics.mean(float(r['cpu_pct']) for r in valid):.0f}%"
                ram_peak_str = f"{max(float(r['ram_mb']) for r in sys_rows):.0f}"

        cpu_proxy_str = f"{s['cpu_proxy']:.1f}%" if s["cpu_proxy"] > 0 else "n/a"
        lag_p50_str   = f"{s['lag_p50']:.1f}" if s["lag_p50"] > 0 else "n/a"
        lag_peak_str  = f"{s['lag_peak']:.1f}" if s["lag_peak"] > 0 else "n/a"

        vals = [
            plat,
            f"{s['duration']:.1f}",
            f"{s['js_peak']:.1f}",
            f"{s['wasm_peak']:.1f}",
            lag_p50_str,
            lag_peak_str,
            cpu_proxy_str,
            cpu_real_str,
            ram_peak_str,
        ]
        print("  " + "  ".join(v.rjust(w) for v, w in zip(vals, widths)))

    print()
    print("  * CPU real% and RAM peak(MB) = Chrome renderer process (psutil)")
    print("    available only for platforms run with drive_batch.py --chrome-pid")
