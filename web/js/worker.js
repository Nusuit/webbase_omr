importScripts(
  "../js/ort.min.js",
  "./worker-protocol.js?v=20260328-yolo2",
  "./wasm-bridge.js?v=20260328-yolo2",
  "/wasm/omr.js?v=20260328-yolo2"
);

const bridge = new WasmBridge();
let ready = false;
let yoloSession = null;

const YOLO_INPUT_SIZE = 640;           // v2 model trained at 640×640 (CPU-friendly, regions are large)

// v2 model classes: 0=marker_corners, 1=id_keycode, 2=questions_1_20, 3=questions_21_60
const MARKER_CLASS_ID   = 0;          // use marker_corners bbox to crop paper region
const CONF_THRESHOLD    = 0.50;       // v2 model is trained on real-world shots; 0.5 is sufficient
const IOU_THRESHOLD     = 0.45;
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
 * Run YOLO v2 model on an ImageData.
 * Detects all 4 classes; returns the marker_corners (class 0) bbox in original
 * image coordinates as { x1, y1, x2, y2 }, or null if not found.
 * The marker_corners box is used to crop+mask the paper region before C++ processing.
 */
async function detectMarkerRegion(imageData) {
  const session = await ensureYolo();
  const { data: lbData, scale, padX, padY } = letterboxImageData(imageData, YOLO_INPUT_SIZE);
  const tensorData = imageDataToTensor(lbData, YOLO_INPUT_SIZE);
  const inputTensor = new ort.Tensor("float32", tensorData, [1, 3, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE]);
  const feeds = { [session.inputNames[0]]: inputTensor };
  const results = await session.run(feeds);

  // YOLOv8 output: [1, 4+nc, num_anchors]
  const raw = results[session.outputNames[0]];
  const numAnchors = raw.dims[2];
  const data2 = raw.data;
  const NC = 4; // marker_corners, id_keycode, questions_1_20, questions_21_60

  const candidates = [];
  for (let i = 0; i < numAnchors; i++) {
    const cx = data2[0 * numAnchors + i];
    const cy = data2[1 * numAnchors + i];
    const bw = data2[2 * numAnchors + i];
    const bh = data2[3 * numAnchors + i];
    const conf = data2[(4 + MARKER_CLASS_ID) * numAnchors + i];
    if (conf < CONF_THRESHOLD) continue;
    const x1 = cx - bw / 2, y1 = cy - bh / 2, x2 = cx + bw / 2, y2 = cy + bh / 2;
    candidates.push([x1, y1, x2, y2, conf]);
  }

  console.log(`[YOLOv2] marker_corners candidates before NMS: ${candidates.length}`);
  const kept = nms(candidates, IOU_THRESHOLD);
  console.log(`[YOLOv2] After NMS: ${kept.length}`);
  if (!kept.length) {
    console.log(`[YOLOv2] No marker_corners detected — falling back to raw image`);
    return null;
  }

  const [bx1, by1, bx2, by2] = kept[0];
  const conf = kept[0][4];
  console.log(`[YOLOv2] marker_corners conf=${conf.toFixed(3)}, letterbox=(${bx1.toFixed(0)},${by1.toFixed(0)})-(${bx2.toFixed(0)},${by2.toFixed(0)})`);

  // Map letterbox → original image coords
  const origX1 = (bx1 - padX) / scale;
  const origY1 = (by1 - padY) / scale;
  const origX2 = (bx2 - padX) / scale;
  const origY2 = (by2 - padY) / scale;

  // Expand by 12% so corner markers at the edges aren't clipped
  const origW = origX2 - origX1;
  const origH = origY2 - origY1;
  const padPct = 0.12;
  const W = imageData.width, H = imageData.height;
  const bbox = {
    x1: Math.max(0, Math.round(origX1 - origW * padPct)),
    y1: Math.max(0, Math.round(origY1 - origH * padPct)),
    x2: Math.min(W - 1, Math.round(origX2 + origW * padPct)),
    y2: Math.min(H - 1, Math.round(origY2 + origH * padPct))
  };

  console.log(`[YOLOv2] Final bbox: (${bbox.x1},${bbox.y1})-(${bbox.x2},${bbox.y2}), Image: ${W}x${H}`);
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

function parseSheetRaw(raw) {
  const status = raw[0];
  const mssvValid = raw[1] === 1;
  const mssvDigits = Array.from(raw.slice(2, 8));
  const keyValid = raw[8] === 1;
  const keyDigits = Array.from(raw.slice(9, 12));
  const answerMasks = Array.from(raw.slice(12, 72));
  const suspicious = Array.from(raw.slice(72, 132));

  const answers = answerMasks.map((mask, idx) => ({
    q: idx + 1,
    mask,
    selected: decodeAnswerMask(mask),
    suspicious: suspicious[idx] === 1
  }));

  return {
    status,
    mssvValid,
    mssv: mssvValid ? mssvDigits.join("") : null,
    mssvDigits,
    keyValid,
    examCode: keyValid ? keyDigits.join("") : null,
    keyDigits,
    answeredCount: answers.filter((a) => a.mask !== 0).length,
    suspiciousCount: answers.filter((a) => a.suspicious).length,
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
      const { width, height, buffer } = msg.payload;
      const rgba = new Uint8ClampedArray(buffer);

      // ── v2: YOLO marker_corners → mask → C++ ──────────────────────────────────
      // Run YOLOv8n (trained on 4-class OMR data) to locate the marker_corners region,
      // mask the background, then hand the clean image to C++ NormalizeSheet.
      // Falls back to raw image if YOLO doesn't fire (same behaviour as staging).
      const wasmMemBefore = bridge.module ? bridge.module.HEAPU8.byteLength : 0;
      const t_worker_start = performance.now();

      const imageData = new ImageData(rgba, width, height);
      const t_yolo_start = performance.now();
      const markerBox = await detectMarkerRegion(imageData);
      const t_yolo_end = performance.now();

      let processRgba = rgba, processW = width, processH = height;
      if (markerBox) {
        const masked = maskImageOutsideBox(imageData, markerBox);
        processRgba = new Uint8ClampedArray(masked.data);
        console.log(`[worker] YOLO masked image ${processW}x${processH} → C++ (marker_corners crop)`);
      } else {
        console.log(`[worker] YOLO miss — passing raw image ${processW}x${processH} to C++`);
      }

      const t_cpp_start = performance.now();
      const { status, result, raw, preview, warpedPreview, previewWidth, previewHeight } =
        bridge.processSheet(processRgba, processW, processH);
      const t_cpp_end = performance.now();

      const wasmMemAfter = bridge.module ? bridge.module.HEAPU8.byteLength : 0;
      const parsed = parseSheetRaw(raw);

      const perf = {
        yolo_ms:         t_yolo_end - t_yolo_start,
        yolo_detected:   markerBox !== null,
        cpp_ms:          t_cpp_end - t_cpp_start,
        worker_total_ms: t_cpp_end - t_worker_start,
        wasm_heap_before: wasmMemBefore,
        wasm_heap_after:  wasmMemAfter,
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
