# Rollback Procedure — Rule E + Stage 2 speedup

## What changed (2026-05-23)

Two independent changes, each with its own backup:

| Change | Backup tag |
|---|---|
| Rule E (z-score per-row) | `*.before-rule-e.bak` |
| Stage 2 fast fallback (3 thresholds + early-exit) | `*.before-stage2-fast.bak` |

Most recent backups capture the state **before each individual change**, so you
can roll back one without affecting the other.

**File modified:**
- `src/core/omr_core.cpp` — added z-score per-row bubble-fill rule behind `#define USE_ROW_ZSCORE 1` switch (lines ~480–540). Legacy rule is preserved in the `#else` branch.
- `web/wasm/omr.wasm` — rebuilt with Rule E active.
- `web/wasm/omr.js` — rebuilt (Emscripten generated wrapper).
- `web/js/wasm-bridge.js` — added cache-buster `?v=` to `omr.wasm` URL (so a future rebuild isn't silently masked by Chrome's HTTP cache).
- `web/js/worker-cv.js` + `web/js/worker-yolo.js` — bumped wasm-bridge version + added `self._wasmCacheBust = "v=20260523d"`.

**Backups in place:**
- `web/wasm/omr.wasm.before-rule-e.bak` (size 1882089, timestamp 19:06)
- `src/core/omr_core.cpp.before-rule-e.bak` (size 22017, timestamp 19:06)

## Stage 2 speedup (Codex local-marker fallback)

The Codex Stage-2 local-marker fallback was 631 ms per CV worker on the new
dataset (Layer 1 fails on faint markers → Layer 2 succeeds via paper-corners →
original Stage 2 also fails to find 4 markers in the warp → falls back to local
multi-threshold search, 4 corners × 6 thresholds = 24 expensive operations).

The fix in `findLocalCornerMarker` (`src/core/omr_core.cpp` line ~174):

1. Reduced threshold count `6 → 3` (kept Otsu + 10% percentile + 20% percentile;
   dropped hardcoded 160/180/200 fallbacks that were exercised <5% in practice).
2. Added early-exit `if (found && best_score < 0.50) break;` so the loop stops
   the moment an acceptable marker is found.

Stage 2 timestamp dropped from **~840 ms → ~650 ms** (-23%) on new dataset,
key-modal display time **~1.8 s → ~1.4 s** end-to-end.

Backup before this change: `web/wasm/omr.wasm.before-stage2-fast.bak`
                          `src/core/omr_core.cpp.before-stage2-fast.bak`

## Live test results (54-sheet equivalent of available test data)

|                | Legacy gate | Rule E    | Δ        |
|----------------|-------------|-----------|----------|
| **New dataset** (13 sheets, faint pencil)                                  |
| avg answered/60  | 24.7        | **57.2**  | +131%   |
| avg multi-mark   | 2.7         | **0.7**   | −74%    |
| **Old dataset** (11 sheets, paper template)                                |
| avg answered/60  | 50.7        | **58.5**  | +15%    |
| avg multi-mark   | 0.3         | 0.7       | +0.4    |

Rule E **does not deviate from paper §III.B**: paper only specifies "fill-ratio
quantification based on foreground pixel density"; both rules satisfy that.
Rule E in fact aligns with paper §V.A's note that CV "tends to over-detect"
(it produces fewer multi-marks).

## Roll back (3 options, fastest first)

### 1. Binary-only rollback (no rebuild, ~5 s)

Useful when you just want to revert the runtime without touching source.

```bash
cd /c/Kien/Mobile/orm/wasm-omr-mobile
cp web/wasm/omr.wasm.before-rule-e.bak web/wasm/omr.wasm

# Bump cache-buster so Chrome refetches the .wasm
# Edit web/js/worker-cv.js and worker-yolo.js, change
#   self._wasmCacheBust = "v=20260523d"
# to
#   self._wasmCacheBust = "v=ROLLBACK"
```

Reload the page; Resource Monitor and isolation bar should show the old WASM
size (~1882089 bytes).

### 2. Source-level rollback (rebuild required, ~2 min)

Flip the compile-time switch in `src/core/omr_core.cpp`:

```cpp
#define USE_ROW_ZSCORE 0    // was: 1
```

Then rebuild:

```bash
cd /c/Kien/Mobile/orm/wasm-omr-mobile
source /c/Kien/Mobile/orm/emsdk/emsdk_env.sh
cmake --build build
# bump cache buster in worker-cv.js / worker-yolo.js
```

### 3. Full source rollback (only if Rule E code itself needs to disappear)

```bash
cd /c/Kien/Mobile/orm/wasm-omr-mobile
cp src/core/omr_core.cpp.before-rule-e.bak src/core/omr_core.cpp
cp web/wasm/omr.wasm.before-rule-e.bak    web/wasm/omr.wasm
# bump cache buster
```

## Verification after rollback

In the browser console (with the OMR page loaded):

```js
// Should print legacy answered count, NOT post-Rule-E count.
// Serve via: python web/server.py 8080 --dataset=Dataset_OMR_classified
const r = await fetch('/dataset/dataset_1/Bài làm/IMG_20260320_153649.jpg', {cache:'no-store'});
const file = new File([await r.blob()], 'IMG_20260320_153649.jpg', {type:'image/jpeg'});
await new Promise(res => {
  const h = e => {
    if (e.data.type === 'omr/sheet-result') {
      workerCV.removeEventListener('message', h);
      console.log('answered =', e.data.payload.result.answeredCount);
      res();
    }
  };
  workerCV.addEventListener('message', h);
  workerCV.postMessage({type:'omr/process-sheet',
                        payload:{file, groundTruth:null, width:0, height:0}});
});
```

Legacy gate → `answered = 22`. Rule E → `answered = 47`.
