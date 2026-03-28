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
  // Map from letterbox coords → original image coords
  const origX1 = (bx1 - padX) / scale;
  const origY1 = (by1 - padY) / scale;
  const origX2 = (bx2 - padX) / scale;
  const origY2 = (by2 - padY) / scale;
  const W = imageData.width, H = imageData.height;
  return {
    x1: Math.max(0, Math.round(origX1)),
    y1: Math.max(0, Math.round(origY1)),
    x2: Math.min(W - 1, Math.round(origX2)),
    y2: Math.min(H - 1, Math.round(origY2))
  };
}

/**
 * Crops the paper region from full ImageData and resizes to 1700×2400,
 * but adds a pure WHITE border around the YOLO box.
 * This guarantees OpenCV Stage 2 can isolate the marker centers perfectly,
 * resolving the center-to-edge offset discrepancy without compiling C/C++.
 */
function cropAndResize(imageData, box) {
  const { x1, y1, x2, y2 } = box;
  const bw = x2 - x1, bh = y2 - y1;
  
  // 3% white padding to prevent markers from touching image boundaries
  const padX = Math.round(bw * 0.03);
  const padY = Math.round(bh * 0.03);
  const paddedW = bw + padX * 2;
  const paddedH = bh + padY * 2;

  const srcCanvas = new OffscreenCanvas(imageData.width, imageData.height);
  srcCanvas.getContext("2d").putImageData(imageData, 0, 0);

  const dstCanvas = new OffscreenCanvas(1700, 2400);
  const ctx = dstCanvas.getContext("2d");
  
  // Fill with pure white background
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, 1700, 2400);
  
  // Draw YOLO crop centered with padding factored in
  const drawX = (padX / paddedW) * 1700;
  const drawY = (padY / paddedH) * 2400;
  const drawW = (bw / paddedW) * 1700;
  const drawH = (bh / paddedH) * 2400;
  
  ctx.drawImage(srcCanvas, x1, y1, bw, bh, drawX, drawY, drawW, drawH);
  return dstCanvas.getContext("2d").getImageData(0, 0, 1700, 2400);
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

      // ── YOLO: detect paper bbox and pre-crop to 1700×2400 ─────────────────
      let processRgba = rgba;
      let processW = width, processH = height;

      try {
        const imgData = new ImageData(rgba, width, height);
        const box = await detectPaper(imgData);
        if (box) {
          const cropped = cropAndResize(imgData, box);
          processRgba = new Uint8ClampedArray(cropped.data.buffer);
          processW = 1700;
          processH = 2400;
        }
      } catch (yoloErr) {
        // YOLO failed → fall through to WASM with original image (graceful degrade)
        console.warn("[worker] YOLO paper detect failed, using raw image:", yoloErr);
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
