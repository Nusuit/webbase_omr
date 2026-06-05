"""Regenerate figures/fig1_latency.pdf from per-sheet JSON in runs/batch_results/.

For Web hosts (Chrome WebGPU+SIMD+Threads on x86 Win and ARM Android, Safari on
iOS): plots the per-sheet C++ OMR worker timer cpp_ms (mean + SD), N=179.
For Web YOLO rows, model-inference time is outside cpp_ms unless a run exports
worker_total_ms separately.

For Native Android: plots full-pipeline E2E e2e_ms (mean + SD), N=179.
"""
import json
import statistics
from pathlib import Path

import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
BR = ROOT / "runs" / "batch_results"
OUT = ROOT / "figures" / "fig1_latency.pdf"


def load_mean_sd(path: Path, field: str) -> tuple[float, float]:
    data = json.load(path.open())
    vals = [r[field] for r in data if r.get("status") == 0 and r.get(field) is not None]
    return statistics.mean(vals), statistics.stdev(vals)


HOSTS = [
    # (display label, cv json, yolo json, field, group)
    ("Desktop ref",       "PC_gaming_cv_n179.json",                  "PC_gaming_yolo_n179.json",                  "cpp_ms", "Web Edge (Chrome WebGPU+SIMD+Threads)"),
    ("Vivobook",          "asus_vivobook_cv_n179.json",              "asus_vivobook_yolo_n179.json",              "cpp_ms", "Web Edge (Chrome WebGPU+SIMD+Threads)"),
    ("Nitro 5",           "acer_nitro5_cv_n179.json",                "acer_nitro5_yolo_n179.json",                "cpp_ms", "Web Edge (Chrome WebGPU+SIMD+Threads)"),
    ("Mac M1",            "mac_air_m1_cv_n179.json",                 "mac_air_m1_yolo_n179.json",                 "cpp_ms", "Web Edge (Chrome WebGPU+SIMD+Threads)"),
    ("Redmi (Web)",       "redmi_note13_pro_plus_cv_n179.json",      "redmi_note13_pro_plus_yolo_raw_n179.json",  "cpp_ms", "Web Edge (Chrome WebGPU+SIMD+Threads)"),
    ("iPhone 16",         "iphone_16_cv_n179.json",                  "iphone_16_yolo_n179.json",                  "cpp_ms", "Web Edge (Safari SIMD)"),
]

# Native: one CV plus three YOLO providers
NATIVE = [
    ("Redmi (Native CV)",         "redmi_note13_pro_plus_native_cv_n179.json",         None,                                                "e2e_ms"),
    ("Redmi (Native CPU-1T)",     None,                                                "redmi_note13_pro_plus_native_yolo_cpu1_n179.json",  "e2e_ms"),
    ("Redmi (Native CPU-4T)",     None,                                                "redmi_note13_pro_plus_native_yolo_cpu4_n179.json",  "e2e_ms"),
    ("Redmi (Native NNAPI)",      None,                                                "redmi_note13_pro_plus_native_yolo_nnapi_n179.json", "e2e_ms"),
]


def main() -> None:
    labels: list[str] = []
    cv_means: list[float] = []
    cv_sds: list[float] = []
    yolo_means: list[float] = []
    yolo_sds: list[float] = []

    for name, cv_file, yolo_file, field, _ in HOSTS:
        labels.append(name)
        if cv_file is not None:
            m, s = load_mean_sd(BR / cv_file, field)
            cv_means.append(m); cv_sds.append(s)
        else:
            cv_means.append(0); cv_sds.append(0)
        if yolo_file is not None:
            m, s = load_mean_sd(BR / yolo_file, field)
            yolo_means.append(m); yolo_sds.append(s)
        else:
            yolo_means.append(0); yolo_sds.append(0)

    for name, cv_file, yolo_file, field in NATIVE:
        labels.append(name)
        if cv_file is not None:
            m, s = load_mean_sd(BR / cv_file, field)
            cv_means.append(m); cv_sds.append(s)
        else:
            cv_means.append(0); cv_sds.append(0)
        if yolo_file is not None:
            m, s = load_mean_sd(BR / yolo_file, field)
            yolo_means.append(m); yolo_sds.append(s)
        else:
            yolo_means.append(0); yolo_sds.append(0)

    n = len(labels)
    x = list(range(n))
    w = 0.4

    fig, ax = plt.subplots(figsize=(7.0, 3.6))
    cv_x = [i - w / 2 for i in x]
    yo_x = [i + w / 2 for i in x]

    ax.bar(cv_x, cv_means, w, yerr=cv_sds, capsize=2, label="CV",
           color="#4a7fb7", edgecolor="#13325e", linewidth=0.4)
    ax.bar(yo_x, yolo_means, w, yerr=yolo_sds, capsize=2, label="YOLO+CV",
           color="#d96b3a", edgecolor="#7a3210", linewidth=0.4)

    for i, (cm, ym) in enumerate(zip(cv_means, yolo_means)):
        if cm > 0:
            ax.text(cv_x[i], cm * 1.04, f"{cm:.0f}", ha="center", va="bottom",
                    fontsize=6.5)
        if ym > 0:
            ax.text(yo_x[i], ym * 1.04, f"{ym:.0f}", ha="center", va="bottom",
                    fontsize=6.5)

    ax.set_yscale("log")
    ax.set_ylim(50, 3000)
    ax.set_ylabel("Per-sheet latency (ms, log scale)")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=30, ha="right", fontsize=7.5)
    ax.legend(loc="upper left", fontsize=8, frameon=False)
    ax.axvline(x=5.5, color="#888888", linestyle=":", linewidth=0.6)
    ax.axvline(x=6.5, color="#888888", linestyle=":", linewidth=0.6)
    ax.text(2.5, 2400, "Web Edge (Chrome + SIMD/Threads, Safari SIMD)",
            ha="center", fontsize=7, color="#444444")
    ax.text(8.0, 2400, "Native Android (ONNX RT)",
            ha="center", fontsize=7, color="#444444")
    ax.grid(axis="y", alpha=0.3, linewidth=0.4)
    ax.set_axisbelow(True)

    fig.tight_layout()
    fig.savefig(OUT, format="pdf", bbox_inches="tight")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
