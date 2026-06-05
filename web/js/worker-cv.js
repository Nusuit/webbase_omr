// METHOD: Traditional OpenCV (staging) — no YOLO masking in PROCESS_SHEET

// Detect WASM capabilities and select the best available omr module.
// SIMD probe: minimal wasm binary that uses i32x4.splat (SIMD128 proposal).
const _SIMD_PROBE = new Uint8Array([
  0,97,115,109,1,0,0,0,1,5,1,96,0,1,123,3,2,1,0,10,10,1,8,0,65,0,253,15,253,98,11
]);
const _hasSIMD    = WebAssembly.validate(_SIMD_PROBE);
const _hasThreads = typeof SharedArrayBuffer !== "undefined";
const _omrVariant = (_hasThreads && _hasSIMD) ? "omr_threads"
                  : _hasSIMD                  ? "omr_simd"
                  :                             "omr";
console.log(`[worker-cv] SIMD=${_hasSIMD} SAB=${_hasThreads} → /wasm/${_omrVariant}.js`);

importScripts(
  "../js/ort.min.js",
  "./worker-protocol.js?v=20260328-yolo2",
  "./wasm-bridge.js?v=20260523-ruleE"
);

// Try the best variant first; fall back to baseline if the file hasn't been built yet.
// `?v=` busts Chrome's HTTP cache for the worker importScripts URL; without it, a
// rebuilt omr.js + omr.wasm pair can be served from cache and silently keep the
// old C++ behaviour. The worker URL itself is busted in app.js via Date.now().
let _loadedVariant = _omrVariant;
self._wasmCacheBust = "v=20260523f";
try {
  importScripts(`/wasm/${_omrVariant}.js?${self._wasmCacheBust}`);
} catch (_e) {
  _loadedVariant = "omr";
  console.warn(`[worker-cv] /wasm/${_omrVariant}.js not found — falling back to omr.js`);
  importScripts(`/wasm/omr.js?${self._wasmCacheBust}`);
}

const bridge = new WasmBridge();
let ready = false;
let yoloSession = null;

let pipelineStartTs = 0;
let cppLogs = [];
self.onCppLog = (text) => {
  if (pipelineStartTs) {
    cppLogs.push({ ts: performance.now() - pipelineStartTs, msg: text });
  }
};

const YOLO_INPUT_SIZE = 1280;          // model input: 1280×1280
const PAPER_CLASS_ID = 0;             // "paper" is class 0
const CONF_THRESHOLD = 0.84;          // Only accept confident detections like student sheets (0.84+)
                                      // Answer keys (0.508) fall back to raw image + adaptive histogram
const IOU_THRESHOLD  = 0.45;
const ROI_RATIO = { x: 0.25, y: 0.25, w: 0.5, h: 0.5 };

// ─── YOLO utilities ───────────────────────────────────────────────────────────

/** Load model once, cached globally. */
async function ensureYolo() {
  if (yoloSession) return yoloSession;
  // Point to self-hosted WASM binaries (avoids CDN CSP issues on Vercel)
  ort.env.wasm.wasmPaths = "/wasm/";
  ort.env.wasm.numThreads = 1; // Force non-threaded WASM because threaded files (.wasm) are absent
  yoloSession = await ort.InferenceSession.create("../models/paper_detect.onnx", {
    executionProviders: ["wasm"]
  });
  return yoloSession;
}

/** Letterbox-resize source ImageData to squareSize×squareSize. Returns {data, pad} */
function letterboxImageData(imgData, squareSize) {
  const sw = imgData.width, sh = imgData.height;
  const scale = Math.min(squareSize / sw, squareSize / sh);
  const nw = Math.round(sw * scale), nh = Math.round(sh * scale);
  const padX = Math.round((squareSize - nw) / 2);
  const padY = Math.round((squareSize - nh) / 2);

  const offscreen = new OffscreenCanvas(squareSize, squareSize);
  const ctx = offscreen.getContext("2d");
  ctx.fillStyle = "#6e6e6e";
  ctx.fillRect(0, 0, squareSize, squareSize);

  const srcCanvas = new OffscreenCanvas(sw, sh);
  srcCanvas.getContext("2d").putImageData(imgData, 0, 0);
  ctx.drawImage(srcCanvas, padX, padY, nw, nh);

  const lb = ctx.getImageData(0, 0, squareSize, squareSize);
  return { data: lb, scale, padX, padY };
}

/** Convert ImageData (RGBA) to Float32 CHW tensor, normalized 0-1. */
function imageDataToTensor(imageData, size) {
  const { data } = imageData;
  const n = size * size;
  const tensor = new Float32Array(3 * n);
  for (let i = 0; i < n; i++) {
    tensor[0 * n + i] = data[i * 4 + 0] / 255;
    tensor[1 * n + i] = data[i * 4 + 1] / 255;
    tensor[2 * n + i] = data[i * 4 + 2] / 255;
  }
  return tensor;
}

/** NMS over [x1,y1,x2,y2,score] boxes. */
function nms(boxes, iouThresh) {
  boxes.sort((a, b) => b[4] - a[4]);
  const keep = [];
  const suppressed = new Uint8Array(boxes.length);
  for (let i = 0; i < boxes.length; i++) {
    if (suppressed[i]) continue;
    keep.push(boxes[i]);
    for (let j = i + 1; j < boxes.length; j++) {
      if (suppressed[j]) continue;
      const inter = Math.max(0, Math.min(boxes[i][2], boxes[j][2]) - Math.max(boxes[i][0], boxes[j][0])) *
                    Math.max(0, Math.min(boxes[i][3], boxes[j][3]) - Math.max(boxes[i][1], boxes[j][1]));
      const union = (boxes[i][2]-boxes[i][0])*(boxes[i][3]-boxes[i][1]) +
                    (boxes[j][2]-boxes[j][0])*(boxes[j][3]-boxes[j][1]) - inter;
      if (union > 0 && inter / union > iouThresh) suppressed[j] = 1;
    }
  }
  return keep;
}

/**
 * Run YOLO on an ImageData. Returns the best "paper" box as
 * { x1, y1, x2, y2 } in ORIGINAL image coordinates, or null.
 */
async function detectPaper(imageData) {
  const session = await ensureYolo();
  const { data: lbData, scale, padX, padY } = letterboxImageData(imageData, YOLO_INPUT_SIZE);
  const tensorData = imageDataToTensor(lbData, YOLO_INPUT_SIZE);
  const inputTensor = new ort.Tensor("float32", tensorData, [1, 3, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE]);
  const feeds = { [session.inputNames[0]]: inputTensor };
  const results = await session.run(feeds);
  const output = results[session.outputNames[0]].data; // shape [1, 5, N] or [1, N, 5]

  // YOLOv8 output shape is [1, nc+4, num_anchors]; rows = [cx,cy,w,h, cls_scores...]
  // Iterate anchors
  const raw = results[session.outputNames[0]];
  const [, rows, cols] = raw.dims.length === 3
    ? [raw.dims[0], raw.dims[1], raw.dims[2]]
    : [1, raw.dims[1], raw.dims[2]]; // [1, 5, 33600] → transpose

  const data2 = raw.data;
  const candidates = [];
  // YOLOv8 exports as [1, 4+nc, num_anchors]
  const numAnchors = cols;
  for (let i = 0; i < numAnchors; i++) {
    const cx  = data2[0 * numAnchors + i];
    const cy  = data2[1 * numAnchors + i];
    const bw  = data2[2 * numAnchors + i];
    const bh  = data2[3 * numAnchors + i];
    const conf = data2[(4 + PAPER_CLASS_ID) * numAnchors + i];
    if (conf < CONF_THRESHOLD) continue;
    const x1 = cx - bw / 2, y1 = cy - bh / 2, x2 = cx + bw / 2, y2 = cy + bh / 2;
    candidates.push([x1, y1, x2, y2, conf]);
  }

  console.log(`[YOLO] Raw candidates before NMS: ${candidates.length}`);
  const kept = nms(candidates, IOU_THRESHOLD);
  console.log(`[YOLO] After NMS: ${kept.length}`);
  if (!kept.length) {
    console.log(`[YOLO] No detections passed NMS!`);
    return null;
  }

  const [bx1, by1, bx2, by2] = kept[0];
  const conf = kept[0][4];
  console.log(`[YOLO] Top detection: conf=${conf.toFixed(3)}, bbox_area=${((bx2-bx1)*(by2-by1)).toFixed(0)}, letterbox_coords=(${bx1.toFixed(0)},${by1.toFixed(0)})-(${bx2.toFixed(0)},${by2.toFixed(0)})`);
  // Map from letterbox coords → original image coords
  const origX1 = (bx1 - padX) / scale;
  const origY1 = (by1 - padY) / scale;
  const origX2 = (bx2 - padX) / scale;
  const origY2 = (by2 - padY) / scale;

  // Expand bbox by 12% to ensure corner markers at edges aren't clipped during masking
  const origW = origX2 - origX1;
  const origH = origY2 - origY1;
  const padPct = 0.12;
  const x1_exp = origX1 - origW * padPct;
  const y1_exp = origY1 - origH * padPct;
  const x2_exp = origX2 + origW * padPct;
  const y2_exp = origY2 + origH * padPct;

  const W = imageData.width, H = imageData.height;
  const bbox = {
    x1: Math.max(0, Math.round(x1_exp)),
    y1: Math.max(0, Math.round(y1_exp)),
    x2: Math.min(W - 1, Math.round(x2_exp)),
    y2: Math.min(H - 1, Math.round(y2_exp))
  };

  console.log(`[YOLO] Confidence: ${conf.toFixed(3)}, Bbox: (${bbox.x1},${bbox.y1}) to (${bbox.x2},${bbox.y2}), Image: ${W}x${H}`);
  return bbox;
}

/**
 * Masks out everything outside the YOLO detection box.
 * Instead of extracting a tightly stretched 1700x2400 (which ruins 3D perspective homography),
 * we return the original full-size photo, but with the entire desk/background blacked out.
 * This forces C++ Stage 1 (NormalizeSheet) to effortlessly find the 4 precise corners
 * of the tilted paper paper without distraction, and apply a mathematically perfect homography.
 */
function maskImageOutsideBox(imageData, box) {
  const { x1, y1, x2, y2 } = box;
  const boxW = x2 - x1, boxH = y2 - y1;
  const boxArea = boxW * boxH;
  const imgArea = imageData.width * imageData.height;
  console.log(`[Masking] Box: (${x1},${y1})-(${x2},${y2}), size=${boxW}x${boxH}, area_ratio=${(boxArea/imgArea*100).toFixed(1)}%`);

  const canvas = new OffscreenCanvas(imageData.width, imageData.height);
  const ctx = canvas.getContext("2d");

  // Fill background with black (so desk noise vanishes completely)
  ctx.fillStyle = "#000000";
  ctx.fillRect(0, 0, imageData.width, imageData.height);

  // Put the original imageData into a temp canvas
  const tempCanvas = new OffscreenCanvas(imageData.width, imageData.height);
  tempCanvas.getContext("2d").putImageData(imageData, 0, 0);

  // Cut a window to reveal only the YOLO-detected paper
  ctx.drawImage(tempCanvas, x1, y1, boxW, boxH, x1, y1, boxW, boxH);

  return ctx.getImageData(0, 0, imageData.width, imageData.height);
}

// ─── Answer decoding (unchanged) ─────────────────────────────────────────────
function decodeAnswerMask(mask) {
  const options = ["A", "B", "C", "D", "E"];
  const out = [];
  for (let i = 0; i < options.length; i += 1) {
    if (mask & (1 << i)) out.push(options[i]);
  }
  return out;
}

function parseSheetRaw(raw, groundTruth) {
  const status = raw[0];
  const mssvValid = raw[1] === 1;
  const mssvDigits = Array.from(raw.slice(2, 8));
  const keyValid = raw[8] === 1;
  const keyDigits = Array.from(raw.slice(9, 12));
  const answerMasks = Array.from(raw.slice(12, 72));
  const suspicious = Array.from(raw.slice(72, 132));
  const densitiesInt = Array.from(raw.slice(132, 432));

  let tp = 0, fp = 0, tn = 0, fn = 0;
  let multiMarkCount = 0;
  let suspiciousCount = 0;
  let validMultiCount = 0;
  let bubbles = [];

  const answers = answerMasks.map((mask, idx) => {
    let qSuspicious = suspicious[idx] === 1;
    let gtMask = groundTruth ? groundTruth[idx] : 0;
    
    let qFilled = 0;
    let qExpected = 0;
    
    for (let c = 0; c < 5; c++) {
      const isDetected = (mask & (1 << c)) !== 0;
      const isExpected = (gtMask & (1 << c)) !== 0;
      const fillRatio = densitiesInt[idx * 5 + c] / 10000.0;
      const isBubSusp = fillRatio > 0.3 && fillRatio < 0.6; // grey zone
      
      if (isBubSusp) qSuspicious = true;
      if (isDetected) qFilled++;
      if (isExpected) qExpected++;

      if (groundTruth) {
        if (isDetected && isExpected) tp++;
        else if (isDetected && !isExpected) fp++;
        else if (!isDetected && !isExpected) tn++;
        else if (!isDetected && isExpected) fn++;
      }
      
      bubbles.push({
        question: idx + 1,
        choice: c,
        detected: isDetected,
        ground_truth: isExpected,
        fill_ratio: fillRatio,
        suspicious: isBubSusp
      });
    }

    if (qFilled > 1) {
      if (qExpected > 1 && qFilled === qExpected) {
        validMultiCount++;
      } else {
        multiMarkCount++;
        qSuspicious = true;
      }
    }

    if (qSuspicious) suspiciousCount++;

    return {
      q: idx + 1,
      mask,
      selected: decodeAnswerMask(mask),
      suspicious: qSuspicious
    };
  });

  return {
    status,
    mssvValid,
    mssv: mssvValid ? mssvDigits.join("") : null,
    mssvDigits,
    keyValid,
    examCode: keyValid ? keyDigits.join("") : null,
    keyDigits,
    answeredCount: answers.filter((a) => a.mask !== 0).length,
    suspiciousCount,
    multiMarkCount,
    validMultiCount,
    tp, fp, tn, fn,
    bubbles,
    answers
  };
}

// ─── Worker message handler ───────────────────────────────────────────────────
self.onmessage = async (event) => {
  const msg = event.data;
  if (!msg || typeof msg !== "object") {
    self.postMessage({ type: OMR_MSG.ERROR, error: "Invalid worker message" });
    return;
  }

  if (msg.type === OMR_MSG.INIT) {
    try {
      console.log("[worker] METHOD: Traditional OpenCV (staging) — YOLO model loaded but NOT used in sheet processing");
      await bridge.init();
      // Eagerly load YOLO model so first scan is fast
      await ensureYolo().catch(() => {});  // non-fatal if offline
      ready = true;
      self.postMessage({ type: OMR_MSG.READY, protocolVersion: OMR_PROTOCOL_VERSION });
    } catch (err) {
      self.postMessage({ type: OMR_MSG.ERROR, error: `Init failed: ${err.message}` });
    }
    return;
  }

  if (msg.type === OMR_MSG.GET_WASM_HEAP) {
    const bytes = (bridge && bridge.module && bridge.module.HEAPU8) ? bridge.module.HEAPU8.byteLength : 0;
    self.postMessage({ type: OMR_MSG.WASM_HEAP_RESULT, bytes, seq: msg.seq });
    return;
  }

  if (msg.type === OMR_MSG.PROCESS_FRAME) {
    if (!ready) { self.postMessage({ type: OMR_MSG.ERROR, error: "Worker not ready" }); return; }
    try {
      const { width, height, buffer } = msg.payload;
      const rgba = new Uint8ClampedArray(buffer);
      const roi = {
        x: Math.floor(width * ROI_RATIO.x),
        y: Math.floor(height * ROI_RATIO.y),
        w: Math.floor(width * ROI_RATIO.w),
        h: Math.floor(height * ROI_RATIO.h)
      };
      const { blackCount, result } = bridge.processRgbaFrame(rgba, width, height, roi);
      self.postMessage({ type: OMR_MSG.FRAME_RESULT, payload: { width, height, blackCount, roi, buffer: result.buffer } }, [result.buffer]);
    } catch (err) {
      self.postMessage({ type: OMR_MSG.ERROR, error: `Process failed: ${err.message}` });
    }
    return;
  }

  if (msg.type === OMR_MSG.PROCESS_SHEET) {
    if (!ready) { self.postMessage({ type: OMR_MSG.ERROR, error: "Worker not ready" }); return; }
    try {
      const { file, groundTruth } = msg.payload;

      // ── Same-boundary E2E timing ───────────────────────────────────────────────
      // Timer starts BEFORE decode so the Web boundary matches the Native runner
      // (BitmapFactory.decode + bitmapToMat are inside the Native e2e_ms). Stages:
      //   jpeg_decode_ms  = createImageBitmap        ↔ Native jpeg_decode_ms
      //   rgba_extract_ms = drawImage + getImageData ↔ Native bitmap_to_mat_ms
      //   cpp_ms          = C++ NormalizeSheet+read  ↔ Native normalize+thresh+omr
      // e2e_ms = decode + rgba_extract + cpp (no debug-JPEG encode, so it matches
      // Native's e2e_ms MINUS jpeg_write_ms; compare on that matched boundary).
      const t_worker_start = performance.now();
      const bmp = await createImageBitmap(file);
      const t_decode_end = performance.now();
      const width = bmp.width;
      const height = bmp.height;

      const canvas = new OffscreenCanvas(width, height);
      const ctx = canvas.getContext("2d");
      ctx.drawImage(bmp, 0, 0);
      const rgba = ctx.getImageData(0, 0, width, height).data;
      const t_rgba_end = performance.now();

      // ── Traditional OpenCV: pass raw image directly to C++ ─────────────────────
      // C++ NormalizeSheet uses two-layer detection:
      //   Layer 1: FindMarkerCorners (black registration squares) — most reliable
      //   Layer 2: FindPaperCorners (white paper boundary) — fallback
      // No YOLO masking needed — marker corner detection works on raw photos.
      cppLogs = [];
      const wasmMemBefore = bridge.module ? bridge.module.HEAPU8.byteLength : 0;

      const processRgba = rgba;
      const processW = width, processH = height;
      console.log(`[worker] Passing raw image ${processW}x${processH} to C++ (marker corner detection)`);

      pipelineStartTs = performance.now();
      const t_cpp_start = pipelineStartTs;
      const { status, result, raw, preview, warpedPreview, previewWidth, previewHeight } =
        bridge.processSheet(processRgba, processW, processH);
      const t_cpp_end = performance.now();

      const wasmMemAfter = bridge.module ? bridge.module.HEAPU8.byteLength : 0;
      const parsed = parseSheetRaw(raw, groundTruth);

      const perf = {
        yolo_ms:          0,
        yolo_detected:    false,
        jpeg_decode_ms:   t_decode_end - t_worker_start,
        rgba_extract_ms:  t_rgba_end - t_decode_end,
        cpp_ms:           t_cpp_end - t_cpp_start,
        e2e_ms:           t_cpp_end - t_worker_start,
        worker_total_ms:  t_cpp_end - t_worker_start,
        wasm_heap_before: wasmMemBefore,
        wasm_heap_after:  wasmMemAfter,
        cpp_logs:         cppLogs.slice(),
        wasm_variant:     _loadedVariant,
        simd_supported:   _hasSIMD,
        threads_supported: _hasThreads,
      };

      const transfers = [result.buffer];
      if (preview) transfers.push(preview.buffer);
      if (warpedPreview) transfers.push(warpedPreview.buffer);

      self.postMessage(
        { type: OMR_MSG.SHEET_RESULT, payload: { status, width, height, buffer: result.buffer, preview: preview ? preview.buffer : null, warpedPreview: warpedPreview ? warpedPreview.buffer : null, previewWidth, previewHeight, result: parsed, perf } },
        transfers
      );
    } catch (err) {
      self.postMessage({ type: OMR_MSG.ERROR, error: `Sheet process failed: ${err.message}` });
    }
  }
};
