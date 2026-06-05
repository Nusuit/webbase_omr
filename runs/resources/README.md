# Resource Artifacts

This folder stores CPU/RAM/resource CSVs and generated resource plots.

## Instrument Boundaries

Resource numbers are instrument-specific:

- Windows Chrome rows use desktop process sampling (`psutil`) plus browser CSVs.
- macOS/iOS/Android Web rows use browser-exposed heap/WASM memory and in-page
  event-loop proxy values.
- Native Android rows use Android-native/in-app sampler outputs and raw pulls
  under `runs/_native_raw*`.

Do not compare CPU percentages across instruments as direct CPU-efficiency
ratios. The manuscript should phrase these as bounded-resource evidence under
the measured workload.

## Current Platform Folders

- `PC_gaming/`
- `asus_vivobook/`
- `acer_nitro5/`
- `mac_air_m1/`
- `iphone_16/`
- `redmi_note13_pro_plus/`
- `redmi_note13_pro_plus_native/`

## 2026-05-29 Redmi Web Rerun

`redmi_note13_pro_plus_20260529/` contains in-browser resource CSVs for the
fresh Redmi Web CV and YOLO raw reruns. The `_sys.csv` files from that run are
not usable for Android resource claims because desktop `psutil` cannot see the
Android Chrome renderer process through ADB forwarding.

## Plots

Top-level `chart_*.png` and `chart_*.pdf` files are generated figures. Prefer
PDF for the manuscript when the PDF compiles cleanly.

## Legacy and Archived Data

- `legacy/` contains older resource CSVs that were superseded by named platform
  folders.
- `archived/` contains older N=54/profile-stage resource traces and native
  resource captures kept for context, not current 179-sheet table values.
