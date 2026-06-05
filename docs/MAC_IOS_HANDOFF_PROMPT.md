# Handoff prompt — Mac + iPhone (Safari/iOS) benchmark

> **SUPERSEDED (2026-05-30).** The Mac and iOS benchmarks described here are done
> (`runs/batch_results/mac_air_m1_*`, `iphone_16_*`). The "§Recognition Accuracy
> intentionally keeps legacy 96.4%" note is obsolete: the manuscript now uses
> `99.00%` with a real ground truth. Do not reintroduce `96.4%`. Kept for history
> only; safe to delete.

Paste the section below into your VS Code agent (Claude / Cursor /
similar) on Mac. The agent will continue the work where the Windows
machine left off: re-run the same `N=179` web batch benchmark on
**Mac (Safari + Chrome)** and on **iPhone (iOS Safari)**, then update
the paper.

The repo is already pushed to `origin/testing` —
<https://github.com/Nusuit/webbase_omr> (private/personal). Clone via
SSH or HTTPS as you prefer.

---

## Begin agent prompt (copy from here)

You are taking over the OMR cross-platform benchmark project. The
Windows + Android leg is finished and pushed to `origin/testing`
(commit `0ddf00c`). Your job is to add **macOS Web (Safari + Chrome)**
and **iOS Safari** numbers to the paper, plus optionally a native iOS
build via Xcode/WKWebView.

### Repository

- `git clone -b testing git@github.com:Nusuit/webbase_omr.git wasm-omr-mobile`
- `cd wasm-omr-mobile`
- Target file to edit: `omr_etc2026_v8_4_revised.tex`
- Reference plan: `docs/NATIVE_BENCHMARK_INSTRUCTIONS.md` (sister doc;
  not the Mac/iOS plan but useful context on how Native was handled).
- Working artifacts (already in repo):
  - Dataset: `Dataset_OMR_classified/dataset_1..5/Bài làm/` (179 sheets).
  - Web app: `web/` (OpenCV.js + ONNX Runtime Web, batch-detect.html).
  - Scripts: `scripts/drive_batch.py` (CDP driver) and
    `scripts/aggregate_diag.py` (per-sheet → diag JSON).
  - Existing batch results: `runs/batch_results/{pc,mobile}_{cv,yolo}_n179.json`.
  - Resource CSVs: `runs/resources/{pc,mobile,native}/`.

### Background context

- Paper compares Traditional CV vs YOLO+CV across PC Web / Mobile Web
  / Native Android.
- §Recognition Accuracy uses *legacy* numbers (96.4% question-level,
  bubble metrics over 30k bubbles) — these are inherited and we
  intentionally keep them.
- §Cross-Platform Latency, §System Resource Usage, and the
  per-sheet §Diagnostic table use the *new* N=179 evaluation set
  (dataset_1..5).
- Per-sheet diagnostic is deterministic across platforms when the same
  WASM module runs on the same JPEG inputs — confirmed by PC CV ≡
  Mobile CV. So you don't need to re-aggregate `_full_diag.json` for
  Mac/iOS unless you also want a sanity check.

### Concrete tasks

1. **Mac Web (Safari + Chrome)**
   - Start `python3 web/server.py 8080 --dataset=Dataset_OMR_classified`
     (the script is Python 3 stdlib; no extra deps needed).
   - Open Chrome on Mac with remote debugging:
     `open -na "Google Chrome" --args --remote-debugging-port=9222 --user-data-dir=/tmp/chrome-cdp-omr http://localhost:8080/batch-detect.html?method=cv`
   - Run the existing CDP driver — it already works cross-platform:
     `python3 scripts/drive_batch.py pc cv --timeout 2400`
     then `python3 scripts/drive_batch.py pc yolo --timeout 2400`.
     **Caveat:** the existing PC outputs were produced on Windows. To
     avoid clobbering them, change the platform tag — e.g. edit the
     argparse to add a `mac` choice, or pass `--out-prefix mac` and
     adjust `drive_batch.py` accordingly. Save results to
     `runs/batch_results/mac_{cv,yolo}_n179.json` and
     `runs/resources/mac/resources_mac_{cv,yolo}_n179.csv`.
   - **Safari** doesn't expose CDP the same way — use Safari's
     Develop menu (Develop → Show JavaScript Console) and trigger
     `window.__runBatch()` from the console, then read
     `window.__BATCH_RESULTS__` and `window.ResourceMonitor.samples`
     when done. Safari supports WebGPU on macOS 15+ (Sequoia) under
     the `WebGPU` Experimental flag; you may need to enable it in
     Develop → Experimental Features → WebGPU.

2. **iOS Safari (iPhone)**
   - Connect iPhone via USB, enable *Web Inspector* on iPhone:
     Settings → Safari → Advanced → Web Inspector = ON.
   - On Mac Safari: Develop → \<iPhone name\> → \<tab\>. This gives
     remote DevTools; no CDP equivalent, so automation is limited.
   - Easiest path: open
     `http://<mac-ip>:8080/batch-detect.html?method=cv` on the
     iPhone (share the Mac's IP via the same Wi-Fi network or use
     `npx ngrok http 8080` for a tunnel), then click Run on the
     iPhone, and after completion read out
     `window.__BATCH_RESULTS__` from Mac Safari Inspector and save
     as `runs/batch_results/ios_{cv,yolo}_n179.json`.
   - Resource sampling on iOS Safari: the existing `ResourceMonitor`
     uses `performance.now()` (works) and `performance.memory` (does
     **not** exist on Safari). So `js_heap_*` columns will all be 0
     on iOS — report this in the §Threats to validity section.
     The `cpu_load_proxy` (event-loop lag) and `wasm_heap_cv/yolo_mb`
     (queried from WASM) will both work.

3. **Optional: Xcode + WKWebView wrapper**
   - If you want a more "native iOS" comparison: create a minimal
     Xcode WKWebView project that loads
     `http://localhost:8080/batch-detect.html?method=cv` (or an
     embedded copy of `web/`). Code outline:
     ```swift
     import SwiftUI; import WebKit
     struct ContentView: View {
       var body: some View {
         WebView(url: URL(string: "http://<mac-ip>:8080/batch-detect.html?method=cv")!)
       }
     }
     struct WebView: UIViewRepresentable {
       let url: URL
       func makeUIView(context: Context) -> WKWebView { WKWebView() }
       func updateUIView(_ v: WKWebView, context: Context) { v.load(URLRequest(url: url)) }
     }
     ```
     This runs the *same* WebKit engine as Safari but inside an app
     bundle — useful only if you want to claim "iOS native" deployment.
     For the paper's web-vs-native comparison on iOS, Safari is
     equivalent.

4. **Sanity check diagnostic determinism (one-time)**
   - Aggregate Mac CV results:
     `python3 scripts/aggregate_diag.py runs/batch_results/mac_cv_n179.json --out-diag runs/_diag_mac_cv.json --out-stats runs/_stats_mac_cv.json`
   - Compare with `runs/_full_diag.json` (PC CV canonical). All
     `idx/answered/suspicious/multi/marker_fail` entries should
     match exactly. If they don't, that's a finding — likely because
     macOS Chrome compiled the WASM module differently or used a
     different precision floor. Report any divergence to the user.

5. **Update the paper** `omr_etc2026_v8_4_revised.tex`
   - Add Mac and iOS columns/rows to `tab:latency` and
     `tab:resources`. Keep PC Web + Mobile Web + Native Android
     as the primary comparison; Mac and iOS go in as additional
     rows (or as a separate "Extended Platforms" sub-table if the
     primary table gets too wide).
   - Extend §System Resource Usage paragraphs that say
     "three platforms" to clarify which 3 you mean if you've
     introduced 5. Suggested handling:
     - Keep narrative on the original 3.
     - Add a new subsubsection
       `\subsubsection{Extended Platforms (Mac Web, iOS Safari)}`
       that reports the additional measurements concisely.
   - Update §Threats to validity item (ii): currently mentions
     "iOS/Safari evaluation is deferred to future work" — now
     iOS *is* evaluated, so move that to "validated" and add new
     limitations specific to iOS (no `performance.memory`, GPU
     varies by chip, etc.).
   - Compile with `pdflatex omr_etc2026_v8_4_revised.tex` twice.
     The repo's `.gitignore` already excludes `.aux/.log/.out/.pdf`.

6. **Commit + push** when done:
   - Commit message style: single short sentence (no `$(cat <<EOF)`
     hereblocks, no Co-Authored-By trailer — the repo owner prefers
     compact messages).
   - Push to `origin/testing` (current branch).

### Honest constraints I want you to surface

- If WebGPU isn't enabled on Safari, YOLO inference will fall back
  to WASM and timing will be misleading — flag this in the §Latency
  text rather than silently reporting slower numbers as a "Safari
  weakness".
- `performance.memory` missing on Safari ⇒ RAM column will under-
  report on Mac Safari and on all of iOS. Don't fake numbers; report
  what's measurable and acknowledge the gap.
- iOS Safari doesn't support `SharedArrayBuffer` without
  COOP/COEP headers + HTTPS; `web/server.py` is plain HTTP. If you
  want to test SIMD+Threads on iOS, you'll need either:
  (a) `npx http-server -S --cors --proxy http://localhost:8080`
      with a self-signed cert + COOP/COEP headers, or
  (b) Run via ngrok with `--host-header` and add headers manually.
  Otherwise iOS will run the SIMD-only path (no threading).

### Reach out if blocked

If anything is ambiguous (e.g. how to handle the table layout after
adding 2 more platforms, or what to do if Safari WebGPU is unstable),
**stop and ask the repo owner** rather than guessing — the LaTeX is
under active editorial review with the advisor and surprises cost
time.

## End agent prompt
