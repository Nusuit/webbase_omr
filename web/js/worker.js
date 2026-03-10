importScripts("./worker-protocol.js?v=20260307-10", "./wasm-bridge.js?v=20260307-10", "/wasm/omr.js?v=20260307-10");

const bridge = new WasmBridge();
let ready = false;

const ROI_RATIO = { x: 0.25, y: 0.25, w: 0.5, h: 0.5 };

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

  const answeredCount = answers.filter((a) => a.mask !== 0).length;
  const suspiciousCount = answers.filter((a) => a.suspicious).length;

  return {
    status,
    mssvValid,
    mssv: mssvValid ? mssvDigits.join("") : null,
    mssvDigits,
    keyValid,
    examCode: keyValid ? keyDigits.join("") : null,
    keyDigits,
    answeredCount,
    suspiciousCount,
    answers
  };
}

self.onmessage = async (event) => {
  const msg = event.data;

  if (!msg || typeof msg !== "object") {
    self.postMessage({ type: OMR_MSG.ERROR, error: "Invalid worker message" });
    return;
  }

  if (msg.type === OMR_MSG.INIT) {
    try {
      await bridge.init();
      ready = true;
      self.postMessage({ type: OMR_MSG.READY, protocolVersion: OMR_PROTOCOL_VERSION });
    } catch (err) {
      self.postMessage({ type: OMR_MSG.ERROR, error: `Init failed: ${err.message}` });
    }
    return;
  }

  if (msg.type === OMR_MSG.PROCESS_FRAME) {
    if (!ready) {
      self.postMessage({ type: OMR_MSG.ERROR, error: "Worker is not ready" });
      return;
    }

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

      self.postMessage(
        {
          type: OMR_MSG.FRAME_RESULT,
          payload: {
            width,
            height,
            blackCount,
            roi,
            buffer: result.buffer
          }
        },
        [result.buffer]
      );
    } catch (err) {
      self.postMessage({ type: OMR_MSG.ERROR, error: `Process failed: ${err.message}` });
    }
    return;
  }

  if (msg.type === OMR_MSG.PROCESS_SHEET) {
    if (!ready) {
      self.postMessage({ type: OMR_MSG.ERROR, error: "Worker is not ready" });
      return;
    }

    try {
      const { width, height, buffer } = msg.payload;
      const rgba = new Uint8ClampedArray(buffer);
      const { status, result, raw, preview, warpedPreview, previewWidth, previewHeight } = bridge.processSheet(rgba, width, height);
      const parsed = parseSheetRaw(raw);

      const transfers = [result.buffer];
      if (preview) transfers.push(preview.buffer);
      if (warpedPreview) transfers.push(warpedPreview.buffer);

      self.postMessage(
        {
          type: OMR_MSG.SHEET_RESULT,
          payload: {
            status,
            width,
            height,
            buffer: result.buffer,
            preview: preview ? preview.buffer : null,
            warpedPreview: warpedPreview ? warpedPreview.buffer : null,
            previewWidth,
            previewHeight,
            result: parsed
          }
        },
        transfers
      );
    } catch (err) {
      self.postMessage({ type: OMR_MSG.ERROR, error: `Sheet process failed: ${err.message}` });
    }
  }
};

