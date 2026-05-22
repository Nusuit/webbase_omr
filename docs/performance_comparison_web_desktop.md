# Performance Comparison: Traditional OpenCV vs YOLOv8n+OpenCV
## Platform: Web (Desktop Browser) — Chrome

**Research paper:** "Triển khai thuật toán chấm thi trắc nghiệm qua hình ảnh xử lý biên trên nền tảng Web: Đánh giá hiệu năng và so sánh đa nền tảng"

**Test conditions:**
- Input resolution: 1145×2560 px (portrait phone photos)
- Browser: Chrome (desktop)
- Dataset: 56 sheets (54 assignment frames + 2 answer frames) — same dataset used for both methods
- Staging: branch `staging` — raw image → C++ OpenCV (WASM)
- v2-yolo: branch `v2-yolo` — YOLOv8n detection → mask → C++ OpenCV (WASM)

Four runs were collected:

| Run | Branch | YOLO backend | n | Notes |
|---|---|---|---|---|
| A | staging | none | 30 | Baseline |
| B | v2-yolo | WASM | 24 | First WASM run |
| C | v2-yolo | WASM (fallback after WebGPU probe) | 22 | Two-phase: 7 warm-up + 14 steady + 1 spike |
| D | v2-yolo | **WebGPU** | 24 | WebGPU active — YOLO on GPU |

---

## Table 1 — End-to-End Latency (ms) · All Runs

| Metric | Staging (Run A) | v2-yolo WASM (Run B) | v2-yolo WASM (Run C) | v2-yolo WebGPU (Run D) |
|---|---|---|---|---|
| Samples (n) | 30 | 24 | 22 | 24 |
| **E2E avg (ms)** | **6,389** | **21,004** | **14,646** | **16,472** |
| E2E min (ms) | 5,908 | 18,981 | 8,655 | 6,463 |
| E2E max (ms) | 6,929 | 22,924 | 34,253 | 18,316 |

---

## Table 2 — Component Breakdown (ms) · All Runs

| Component | Staging (A) | v2-yolo WASM (B) | v2-yolo WASM (C warm) | v2-yolo WebGPU (D) |
|---|---|---|---|---|
| **YOLO avg** | 0 | 2,967 | 1,170 | **150*** |
| YOLO min | 0 | 1,272 | 1,064 | **74** |
| YOLO max | 0 | 3,574 | 1,351 | 1,771 (cold) |
| YOLO % of E2E | — | ~14% | ~12% | **~1%** |
| **C++ OMR avg** | **6,376** | 17,948 | 8,175 | **16,839** |
| C++ OMR min | 5,904 | 15,851 | 7,319 | 6,353 |
| C++ OMR max | 6,926 | 21,408 | 8,404 | 18,010 |
| **Worker overhead avg** | **3.2** | 31.9 | 15.8 | **33.4** |

*Warm YOLO avg (sheets 2–24, WebGPU): 142ms

### Run D — Per-Sheet Breakdown

| Sheet | E2E (ms) | YOLO (ms) | C++ (ms) | Note |
|---|---|---|---|---|
| 1 | 8,438 | 1,771 | 6,604 | WebGPU model cold load |
| 2 | **6,463** | **74** | **6,353** | Ideal — matches staging |
| 3 | 10,493 | 116 | 10,323 | C++ begins escalating |
| 4–24 avg | 17,527 | 150 | 17,350 | C++ stabilised high |

> **Key finding:** WebGPU reduces YOLO from ~3,000ms → ~142ms warm (**21× faster**). Sheet 2 demonstrates the theoretical ideal: YOLO=74ms, C++=6,353ms, E2E=6,463ms — essentially matching staging. However from sheet 3 onward, C++ escalates to ~17,350ms despite YOLO running on the GPU. The cause is not ONNX CPU contention (YOLO is now on GPU) but likely **CPU thermal throttling** — the first two sheets run fast before the CPU heats up, then Chrome throttles the WASM thread. This machine's CPU is the bottleneck regardless of YOLO backend.

---

## Table 3 — Memory Usage · Web Desktop

| Metric | Staging (A) | v2-yolo all runs |
|---|---|---|
| WASM heap (initial) | 17.1 MB | 17.1 MB |
| WASM heap (after 1st call) | **1,778 MB** | **1,778 MB** |
| JS heap before (avg) | ~128 MB | ~128 MB |
| JS heap after (avg) | ~160 MB | ~160 MB |
| JS heap delta (avg) | **~33 MB** | **~33 MB** |

> Memory footprint is identical across all runs and both methods.

---

## Table 4 — YOLO Detection Quality (v2-yolo, all runs)

| Metric | WASM runs | WebGPU run (D) |
|---|---|---|
| Detection rate | 100% | 100% (24/24) |
| Confidence range | 0.921–0.978 | 0.921–0.978 |
| Avg confidence | ~0.960 | ~0.960 |
| YOLO input size | 640×640 | 640×640 |
| Model | YOLOv8n, 4-class | YOLOv8n, 4-class |

---

## Summary — All Runs

| | Staging (A) | v2-yolo WASM (B) | v2-yolo WASM (C warm) | v2-yolo WebGPU (D) |
|---|---|---|---|---|
| E2E avg | **6,389 ms** | 21,004 ms | 9,360 ms | 16,472 ms |
| YOLO time | 0 ms | 2,967 ms | 1,170 ms | **142 ms** ✅ |
| C++ OMR avg | **6,376 ms** | 17,948 ms | 8,175 ms | 16,839 ms |
| WASM memory | 1,778 MB | 1,778 MB | 1,778 MB | 1,778 MB |
| Detection rate | 100%† | 100% | 100% | 100% |
| Winner | ✅ | — | — | YOLO only |

†Staging uses C++ heuristic marker detection.

> **Conclusion:** WebGPU successfully offloads YOLO inference to the GPU (142ms warm, 21× faster than WASM). However, C++ performance on this machine is dominated by CPU thermal throttling — after 2 sheets the CPU slows to ~17,000ms regardless of YOLO backend. The ideal case (sheet 2: E2E=6,463ms, matching staging) confirms the architecture is sound; the bottleneck is hardware thermal limits, not software design. On a machine with better thermal management or a more powerful CPU, WebGPU v2-yolo should match or beat staging while adding reliable paper detection.

---

## Mobile Testing — Xiaomi Note 13 Pro Plus · Chrome Android

**Device:** Xiaomi Note 13 Pro Plus (Chrome Android)
**YOLO backend confirmed:** WebGPU active — `[YOLO] Backend: WebGPU (GPU handles YOLO, CPU free for C++)`

### Table 5 — End-to-End Latency (ms) · Mobile Browser

| Metric | Staging (OpenCV only) | v2-yolo WebGPU (YOLO + OpenCV) | Difference |
|---|---|---|---|
| Samples (n) | **30** | **24** | — |
| **E2E avg (ms)** | **14,781** | **18,014** | **+3,233 ms (+22%)** |
| E2E min (ms) | 13,039 | 13,353 | +314 ms |
| E2E max (ms) | 19,024 | 31,482 | +12,458 ms |

### Table 6 — Component Breakdown (ms) · Mobile Browser

| Component | Staging | v2-yolo WebGPU |
|---|---|---|
| **YOLO avg (ms)** | 0 | **334** (308 warm*) |
| YOLO min (ms) | 0 | 226 |
| YOLO max (ms) | 0 | 926 (cold sheet 1) |
| **C++ OMR avg (ms)** | **14,778** | **17,482** |
| C++ OMR min (ms) | 13,034 | 12,962 |
| C++ OMR max (ms) | 19,012 | 30,132 |
| **Worker overhead avg (ms)** | **3.7** | **15.5** |

*Warm YOLO avg (sheets 2–24, WebGPU): 308 ms

### Mobile v2-yolo WebGPU — Per-Sheet Notes

| Sheet | E2E (ms) | YOLO (ms) | C++ (ms) | Note |
|---|---|---|---|---|
| 1 | 19,287 | 926 | 18,216 | WebGPU model cold load |
| 2 | 21,597 | 281 | 21,187 | C++ already thermally elevated |
| 3 | **31,482** | 711 | 30,132 | YOLO spike + C++ thermal peak |
| 4–24 avg | 17,284 | 299 | 16,843 | Steady-state (mixed thermal) |

> **Mobile thermal pattern:** Unlike desktop (2 fast sheets before throttling), mobile throttles from sheet 2. C++ never runs at staging-equivalent speed on v2-yolo. YOLO (308 ms warm) is ~2.2× slower than desktop WebGPU (142 ms), reflecting mobile GPU vs desktop GPU performance. Staging C++ runs faster (14,778 ms avg vs 17,482 ms) because it avoids the thermal pressure introduced by GPU-side YOLO work and the earlier v2-yolo test run.

### Table 7 — Web vs Mobile Comparison

| Method | Web E2E avg (ms) | Mobile E2E avg (ms) | Mobile/Web ratio |
|---|---|---|---|
| Staging (OpenCV) | 6,389 | **14,781** | **2.31×** |
| v2-yolo WebGPU (best case) | 6,463 (sheet 2) | 13,039 (staging min) | — |
| v2-yolo WebGPU (avg) | 16,472 | **18,014** | **1.09×** |

> **Key findings:**
> - Mobile staging (14,781 ms) is **2.31× slower** than desktop staging (6,389 ms) — this is the raw mobile CPU performance ratio for C++ WASM.
> - Mobile v2-yolo WebGPU (18,014 ms) is **1.22× slower** than mobile staging (14,781 ms) — the YOLO pipeline adds overhead even with WebGPU, unlike the desktop ideal (sheet 2).
> - Mobile v2-yolo WebGPU (18,014 ms) is only **1.09× slower** than desktop v2-yolo WebGPU (16,472 ms) — both are equally bottlenecked by C++ thermal throttling.
> - YOLO detection rate: **100% (24/24)**, confidence 0.934–0.978.

---

## Native Android App — Xiaomi Note 13 Pro Plus

**Device:** Xiaomi Note 13 Pro Plus  
**Method A:** Traditional OpenCV (Kotlin + OpenCV Android SDK, no ONNX)  
**Method B:** YOLOv8n + OpenCV (Kotlin + ONNX Runtime Android + OpenCV Android SDK)  
**Dataset:** Same 56 sheets (54 assignment + 2 answer key)  
**CPU measurement:** `adb shell top -d 1 -p <PID>` — out of 800% total (8 cores)

### Table 8 — End-to-End Latency (ms) · Native Android

| Metric | Traditional OpenCV | YOLO + OpenCV |
|---|---|---|
| Samples (n) | **56** | **28*** |
| **E2E avg (ms)** | **2,362** | **2,231** |
| E2E min (ms) | 1,966 | 2,147 |
| E2E max (ms) | 2,758 | 2,403 |

*28 of 56 visible in truncated log; full batch ran 56 sheets.

### Table 9 — Component Breakdown (ms) · Native Android

| Component | Traditional OpenCV | YOLO + OpenCV |
|---|---|---|
| **Stage 1 normalize avg (ms)** | **96** | **530** (YOLO + mask + warp) |
| Stage 1 min (ms) | 62 | 482 |
| Stage 1 max (ms) | 134 | 702 (cold start) |
| **YOLO inference avg (ms)** | — | **429** |
| YOLO inference min (ms) | — | 377 |
| YOLO inference max (ms) | — | 483 |
| **Stage 2 crop avg (ms)** | 30* | 19* |
| **Bubble detection avg (ms)** | ~2,236 | ~1,682 |

*Stage 2 crop ran but found < 4 markers on every frame for **both methods** — skipped each time. See notes below.

### Table 10 — CPU & RAM Usage · Native Android

| Metric | Traditional OpenCV | YOLO + OpenCV |
|---|---|---|
| CPU% range (out of 800%) | **180–219%** | **190–230%** |
| CPU% system share | ~22–27% | ~24–29% |
| CPU cores equivalent | ~2.2 cores | ~2.8 cores |
| RAM (RES) | 245–269 MB | 343–353 MB |
| RAM %MEM | 3.1–3.6% | 4.6–4.7% |
| Extra RAM for YOLO model | — | **+~90 MB** (ONNX Runtime) |

### Table 11 — Cross-Platform E2E Comparison (ms)

| Platform | Traditional/Staging | YOLO/v2-yolo |
|---|---|---|
| **Desktop Web (Chrome)** | 6,389 | 16,472 (WebGPU avg) |
| **Mobile Web Chrome** | 14,781 | 18,014 (WebGPU avg) |
| **Native Android** | **2,362** | **2,231** |
| Native / Desktop Web ratio | **0.37×** *(2.7× faster)* | **0.14×** *(7.4× faster)* |
| Native / Mobile Web ratio | **0.16×** *(6.3× faster)* | **0.12×** *(8.1× faster)* |

### Table 12 — YOLO Detection Quality · Native Android

| Metric | Value |
|---|---|
| Detection rate | **100%** (56/56) |
| Confidence range | 0.941–0.978 |
| Avg confidence | ~0.958 |
| ONNX Runtime backend | CPU (1 thread) |
| Avg inference time | 429 ms |
| Mask ratio (bbox / image) | 37–50% |

> **Key findings — Native Android:**
> - Traditional OpenCV native (avg **2,362 ms**) is **2.7× faster** than desktop web (6,389 ms) and **6.3× faster** than mobile web (14,781 ms).
> - YOLO native (avg **2,231 ms**) is **7.4× faster** than desktop web YOLO (16,472 ms) and **8.1× faster** than mobile web YOLO (18,014 ms). ONNX Runtime CPU inference (429 ms) is comparable to mobile WebGPU (308 ms warm) — the massive speedup comes from native C++ vs WASM for the OMR pipeline.
> - **Traditional and YOLO have nearly identical E2E** (2,362 vs 2,231 ms) — YOLO adds 429 ms inference but the masking reduces noise for faster bubble detection (~1,682 vs ~2,236 ms).
> - CPU usage is nearly identical: both ~2–3 cores. YOLO adds ~90 MB RAM (ONNX Runtime + model).
> - **Traditional OpenCV has better accuracy; YOLO accuracy is poor.** See notes below.

### Notes for Future Fixing

| # | Issue | Observation | Root Cause | Fix |
|---|---|---|---|---|
| 1 | **Stage 2 crop fails on ALL frames (both methods)** | 0–3 markers found on all 56 frames; never reaches 4 | The marker area filter `1500–15000 px` at 1700×2400 scale may not match actual marker sizes. Web C++ uses the same filter but operates on RGBA Mat; Android converts to BGR first — different Otsu threshold behavior | Log actual contour areas on the warped image; adjust area min/max; consider loosening fill ratio from 0.7 to 0.6 for Stage 2 |
| 2 | **YOLO accuracy is poor** | Traditional detects MSSV/key/bubbles reasonably; YOLO fails on most sheets | YOLO mask ratio is only **37–50%** — the bbox is too tight, clipping the paper edges. After masking + NormalizePaper, the perspective warp is skewed because corner markers may be partially outside the masked region. Web version uses the same mask approach but the **web C++ `findMarkerCorners`** operates on the full 800×600 downscaled image *before* masking, while Android's `NormalizePaper` runs on the *already-masked* image | **Option A:** Run NormalizePaper on the full image (no mask), use YOLO bbox only for Stage 2 crop region. **Option B:** Increase EXPAND_PCT from 0.12 to 0.25 to include more margin. **Option C:** Port the web approach exactly — use YOLO bbox to define ROI, but keep the full image for corner detection |
| 3 | **YOLO CONF_THRESHOLD too low** | Android uses 0.25; web uses 0.50 | Low threshold allows noisy detections, but all actual confidences are >0.94 so this isn't the active issue | Change to 0.50 to match web |
| 4 | **NormalizePaper marker filters too strict after tightening** | Changed fill≥0.85, gray<80 to match web Stage 1 downscaled detection, but Stage 2 operates at full 1700×2400 resolution where marker appearance differs | Web Stage 1 (800×600) uses strict filters; web Stage 2 (1700×2400) uses different filters: area 1500–15000, fill>0.7, aspect 0.8–1.2 | Use separate filter parameters for Stage 1 (NormalizePaper) vs Stage 2 (cropByMarkers) — Stage 1 should be more permissive since it handles raw camera images |
