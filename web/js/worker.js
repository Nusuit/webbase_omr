importScripts(
  "../js/ort.min.js",
  "./worker-protocol.js?v=20260328-yolo2",
  "./wasm-bridge.js?v=20260328-yolo2",
  "/wasm/omr.js?v=20260328-yolo2"
);

const bridge = new WasmBridge();
let ready = false;
let yoloSession = null;

const YOLO_INPUT_SIZE = 1280;          // model input: 1280×1280
const PAPER_CLASS_ID = 0;             // "paper" is class 0
const CONF_THRESHOLD = 0.25;
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

  const kept = nms(candidates, IOU_THRESHOLD);
  if (!kept.length) return null;

  const [bx1, by1, bx2, by2] = kept[0];
  const conf = kept[0][4];
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
  
  const canvas = new OffscreenCanvas(imageData.width, imageData.height);
  const ctx = canvas.getContext("2d");
  
  // Fill background with black (so desk noise vanishes completely)
  ctx.fillStyle = "#000000";
  ctx.fillRect(0, 0, imageData.width, imageData.height);
  
  // Put the original imageData into a temp canvas
  const tempCanvas = new OffscreenCanvas(imageData.width, imageData.height);
  tempCanvas.getContext("2d").putImageData(imageData, 0, 0);
  
  // Cut a window to reveal only the YOLO-detected paper
  ctx.drawImage(tempCanvas, x1, y1, (x2 - x1), (y2 - y1), x1, y1, (x2 - x1), (y2 - y1));
  
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

      // ── YOLO: detect paper bbox and mask out the desk ───────────────────────────
      let processRgba = rgba;
      let processW = width, processH = height;

      try {
        const imgData = new ImageData(rgba, width, height);
        const box = await detectPaper(imgData);
        if (box) {
          const masked = maskImageOutsideBox(imgData, box);
          processRgba = new Uint8ClampedArray(masked.data.buffer);
          processW = width;
          processH = height;
        } else {
          // Fallback if YOLO cannot detect paper
          console.warn("[worker] YOLO failed (confidence too low), passing raw image to C++.");
          processRgba = rgba;
          processW = width;
          processH = height;
        }
      } catch (yoloErr) {
        // YOLO failed → report to UI so user can debug the Onnx/WebAssembly error
        console.warn("[worker] YOLO paper detect failed:", yoloErr);
        self.postMessage({
          type: OMR_MSG.ERROR,
          error: "Lỗi AI YOLO: " + (yoloErr.message || yoloErr.toString())
        });
        return; // Halt process
      }

      const { status, result, raw, preview, warpedPreview, previewWidth, previewHeight } =
        bridge.processSheet(processRgba, processW, processH);
      const parsed = parseSheetRaw(raw);

      const transfers = [result.buffer];
      if (preview) transfers.push(preview.buffer);
      if (warpedPreview) transfers.push(warpedPreview.buffer);

      self.postMessage(
        { type: OMR_MSG.SHEET_RESULT, payload: { status, width, height, buffer: result.buffer, preview: preview ? preview.buffer : null, warpedPreview: warpedPreview ? warpedPreview.buffer : null, previewWidth, previewHeight, result: parsed } },
        transfers
      );
    } catch (err) {
      self.postMessage({ type: OMR_MSG.ERROR, error: `Sheet process failed: ${err.message}` });
    }
  }
};
