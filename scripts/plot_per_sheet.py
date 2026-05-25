"""Build 4 per-sheet cross-platform charts (CPU/RAM × CV/YOLO).

Chart 1: CPU-CV    — X=detect 1..N, Y=CPU%,  3 lines (Desktop/Mobile/Native)
Chart 2: RAM-CV    — same X, Y=RAM (MB)
Chart 3: CPU-YOLO  — same
Chart 4: RAM-YOLO  — same

Per-platform color (consistent across charts):
  Desktop Web    → blue   (#3b82f6)
  Mobile Web     → green  (#10b981)
  Native Android → red    (#ef4444)

Dataset = 179 sheets across all platforms.

TODO: re-bench Mobile (Chrome on Android via adb tunnel) and Native Android
on the n=179 dataset. Until then, mobile/native n=179 CSV files do NOT exist
and this script will fail on those reads. Run order:
  1. python web/server.py 8080 --dataset=Dataset_OMR_classified
  2. adb reverse tcp:8080 tcp:8080
  3. open http://<phone>:8080/batch-detect.html?method=cv on phone, Export CSV
  4. same for ?method=yolo  -> save as resources_mobile_{cv,yolo}_n179.csv
  5. Native: run Android app's BenchActivity on 179 sheets, then
     scripts/pull_native_resources.sh  -> save as resources_native_{cv,yolo}_n179.csv

Metric harmonisation (in _plot_utils):
  RAM:  Web = js_heap_used + wasm_heap_cv + wasm_heap_yolo (process working mem)
        Native = RSS (resident set size)
  CPU:  Web = cpu_load_proxy * 100 (main-thread saturation, ~100% / core)
        Native = cpu_percent (top-style % of cores × 100)
"""
import matplotlib.pyplot as plt
from _plot_utils import read_csv, per_sheet_web, per_sheet_native, RES_DIR

PALETTE = {
    "Desktop Web":    "#3b82f6",
    "Mobile Web":     "#10b981",
    "Native Android": "#ef4444",
}


def plot_chart(title, ylabel, series, out_path):
    fig, ax = plt.subplots(figsize=(11, 5.5))
    for label, data in series:
        if not data:
            continue
        xs = [d[0] for d in data]
        ys = [d[1] for d in data]
        ax.plot(xs, ys, label=label, color=PALETTE[label],
                linewidth=1.8, marker="o", markersize=3)
    ax.set_xlabel("Detection (sheet) number")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.set_xlim(left=0)
    ax.grid(True, alpha=0.3)
    ax.legend(loc="best", fontsize=10)
    plt.tight_layout()
    plt.savefig(out_path, dpi=130)
    plt.close(fig)
    print(f"  wrote {out_path.name}")


def main():
    desk_cv   = per_sheet_web(read_csv(RES_DIR / "desktop" / "resources_desktop_cv_n179.csv"))
    desk_yolo = per_sheet_web(read_csv(RES_DIR / "desktop" / "resources_desktop_yolo_n179.csv"))
    mob_cv    = per_sheet_web(read_csv(RES_DIR / "mobile"  / "resources_mobile_cv_n179.csv"))
    mob_yolo  = per_sheet_web(read_csv(RES_DIR / "mobile"  / "resources_mobile_yolo_n179.csv"))
    nat_cv    = per_sheet_native(read_csv(RES_DIR / "native" / "resources_native_cv_n179.csv"), "CV")
    nat_yolo  = per_sheet_native(read_csv(RES_DIR / "native" / "resources_native_yolo_n179.csv"), "NNAPI")

    plot_chart(
        "CPU usage per detection — CV pipeline (179 sheets)",
        "CPU usage (% of one core — can exceed 100% on multi-core)",
        [("Desktop Web",    [(s, c) for s, _, c in desk_cv]),
         ("Mobile Web",     [(s, c) for s, _, c in mob_cv]),
         ("Native Android", [(s, c) for s, _, c in nat_cv])],
        RES_DIR / "chart_cpu_cv.png",
    )

    plot_chart(
        "RAM usage per detection — CV pipeline (179 sheets)",
        "Memory (MB)",
        [("Desktop Web",    [(s, r) for s, r, _ in desk_cv]),
         ("Mobile Web",     [(s, r) for s, r, _ in mob_cv]),
         ("Native Android", [(s, r) for s, r, _ in nat_cv])],
        RES_DIR / "chart_ram_cv.png",
    )

    plot_chart(
        "CPU usage per detection — YOLO pipeline (179 sheets, Native = NNAPI provider)",
        "CPU usage (% of one core — can exceed 100% on multi-core)",
        [("Desktop Web",    [(s, c) for s, _, c in desk_yolo]),
         ("Mobile Web",     [(s, c) for s, _, c in mob_yolo]),
         ("Native Android", [(s, c) for s, _, c in nat_yolo])],
        RES_DIR / "chart_cpu_yolo.png",
    )

    plot_chart(
        "RAM usage per detection — YOLO pipeline (179 sheets, Native = NNAPI provider)",
        "Memory (MB)",
        [("Desktop Web",    [(s, r) for s, r, _ in desk_yolo]),
         ("Mobile Web",     [(s, r) for s, r, _ in mob_yolo]),
         ("Native Android", [(s, r) for s, r, _ in nat_yolo])],
        RES_DIR / "chart_ram_yolo.png",
    )


if __name__ == "__main__":
    main()
