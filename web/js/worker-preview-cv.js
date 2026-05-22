// Preview-only CV worker: load the baseline WASM file that exists in web/wasm.
// This avoids probing missing omr_threads/omr_simd glue files on this build.
importScripts(
  "./worker-protocol.js?v=preview-print",
  "./wasm-bridge.js?v=preview-print",
  "../wasm/omr.js"
);

const bridge = new WasmBridge();
let ready = false;
let pipelineStartTs = 0;
let cppLogs = [];

self.onCppLog = (text) => {
  if (pipelineStartTs) {
    cppLogs.push({ ts: performance.now() - pipelineStartTs, msg: text });
  }
};

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

  let tp = 0;
  let fp = 0;
  let tn = 0;
  let fn = 0;
  let multiMarkCount = 0;
  let suspiciousCount = 0;
  let validMultiCount = 0;
  const bubbles = [];

  const answers = answerMasks.map((mask, idx) => {
    let qSuspicious = suspicious[idx] === 1;
    const gtMask = groundTruth ? groundTruth[idx] : 0;
    let qFilled = 0;
    let qExpected = 0;

    for (let c = 0; c < 5; c += 1) {
      const isDetected = (mask & (1 << c)) !== 0;
      const isExpected = (gtMask & (1 << c)) !== 0;
      const fillRatio = densitiesInt[idx * 5 + c] / 10000.0;
      const isBubSusp = fillRatio > 0.3 && fillRatio < 0.6;

      if (isBubSusp) qSuspicious = true;
      if (isDetected) qFilled += 1;
      if (isExpected) qExpected += 1;

      if (groundTruth) {
        if (isDetected && isExpected) tp += 1;
        else if (isDetected && !isExpected) fp += 1;
        else if (!isDetected && !isExpected) tn += 1;
        else if (!isDetected && isExpected) fn += 1;
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
        validMultiCount += 1;
      } else {
        multiMarkCount += 1;
        qSuspicious = true;
      }
    }

    if (qSuspicious) suspiciousCount += 1;

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
    answeredCount: answers.filter((answer) => answer.mask !== 0).length,
    suspiciousCount,
    multiMarkCount,
    validMultiCount,
    tp,
    fp,
    tn,
    fn,
    bubbles,
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

  if (msg.type === OMR_MSG.PROCESS_SHEET) {
    if (!ready) {
      self.postMessage({ type: OMR_MSG.ERROR, error: "Worker not ready" });
      return;
    }

    try {
      const { file, groundTruth } = msg.payload;
      const bmp = await createImageBitmap(file);
      const width = bmp.width;
      const height = bmp.height;
      const canvas = new OffscreenCanvas(width, height);
      const ctx = canvas.getContext("2d");
      ctx.drawImage(bmp, 0, 0);
      bmp.close?.();

      const rgba = ctx.getImageData(0, 0, width, height).data;
      pipelineStartTs = performance.now();
      cppLogs = [];
      const wasmMemBefore = bridge.module ? bridge.module.HEAPU8.byteLength : 0;

      const tCppStart = performance.now();
      const { status, result, raw, preview, warpedPreview, previewWidth, previewHeight } =
        bridge.processSheet(rgba, width, height);
      const tCppEnd = performance.now();
      const wasmMemAfter = bridge.module ? bridge.module.HEAPU8.byteLength : 0;
      const parsed = parseSheetRaw(raw, groundTruth);

      const perf = {
        yolo_ms: 0,
        yolo_detected: false,
        cpp_ms: tCppEnd - tCppStart,
        worker_total_ms: tCppEnd - pipelineStartTs,
        wasm_heap_before: wasmMemBefore,
        wasm_heap_after: wasmMemAfter,
        cpp_logs: cppLogs.slice(),
        wasm_variant: "omr",
        simd_supported: WebAssembly.validate(new Uint8Array([
          0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1,
          123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11
        ])),
        threads_supported: typeof SharedArrayBuffer !== "undefined"
      };

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
            result: parsed,
            perf
          }
        },
        transfers
      );
    } catch (err) {
      self.postMessage({ type: OMR_MSG.ERROR, error: `Sheet process failed: ${err.message}` });
    }
  }
};
