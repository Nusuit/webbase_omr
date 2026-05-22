let worker = null;
let workerReady = false;
let selectedFile = null;
let lastResult = null;
let isProcessing = false;

const els = {
  input: document.getElementById("imageInput"),
  dropInput: document.getElementById("dropInput"),
  dropzone: document.getElementById("dropzone"),
  processBtn: document.getElementById("processBtn"),
  printBtn: document.getElementById("printBtn"),
  downloadBtn: document.getElementById("downloadBtn"),
  notice: document.getElementById("notice"),
  report: document.getElementById("report"),
  statusText: document.getElementById("statusText"),
  fileMetric: document.getElementById("fileMetric"),
  sizeMetric: document.getElementById("sizeMetric"),
  timeMetric: document.getElementById("timeMetric"),
  reportMeta: document.getElementById("reportMeta"),
  resultBadge: document.getElementById("resultBadge"),
  summaryBody: document.getElementById("summaryBody"),
  answerTableWrap: document.getElementById("answerTableWrap"),
  logBox: document.getElementById("logBox"),
  originalCanvas: document.getElementById("originalCanvas"),
  grayCanvas: document.getElementById("grayCanvas"),
  blurCanvas: document.getElementById("blurCanvas"),
  otsuCanvas: document.getElementById("otsuCanvas"),
  warpedCanvas: document.getElementById("warpedCanvas"),
  warpedGrayCanvas: document.getElementById("warpedGrayCanvas"),
  warpedBlurCanvas: document.getElementById("warpedBlurCanvas"),
  adaptiveCanvas: document.getElementById("adaptiveCanvas"),
  blockOverlayCanvas: document.getElementById("blockOverlayCanvas"),
  annotatedCanvas: document.getElementById("annotatedCanvas"),
  densityOverlayCanvas: document.getElementById("densityOverlayCanvas"),
  heatmapCanvas: document.getElementById("heatmapCanvas")
};

const ANSWER_LABELS = ["A", "B", "C", "D", "E"];
const PREVIEW_BLOCKS = [
  { name: "MSSV", qOffset: -1, cols: 6, rows: 10, x1: 78, y1: 860, x2: 456, y2: 1530 },
  { name: "KEY", qOffset: -2, cols: 3, rows: 10, x1: 519, y1: 860, x2: 711, y2: 1530 },
  { name: "Q1-10", qOffset: 0, cols: 5, rows: 10, x1: 880, y1: 860, x2: 1183, y2: 1530 },
  { name: "Q11-20", qOffset: 10, cols: 5, rows: 10, x1: 1249, y1: 856, x2: 1563, y2: 1527 },
  { name: "Q21-30", qOffset: 20, cols: 5, rows: 10, x1: 140, y1: 1604, x2: 447, y2: 2311 },
  { name: "Q31-40", qOffset: 30, cols: 5, rows: 10, x1: 515, y1: 1597, x2: 819, y2: 2303 },
  { name: "Q41-50", qOffset: 40, cols: 5, rows: 10, x1: 882, y1: 1593, x2: 1194, y2: 2311 },
  { name: "Q51-60", qOffset: 50, cols: 5, rows: 10, x1: 1251, y1: 1585, x2: 1568, y2: 2292 }
];

function setStatus(text) {
  els.statusText.textContent = text;
}

function showNotice(text, isError = false) {
  els.notice.textContent = text;
  els.notice.style.display = text ? "block" : "none";
  els.notice.style.borderColor = isError ? "#fecdca" : "#fedf89";
  els.notice.style.background = isError ? "#fef3f2" : "var(--warn-bg)";
  els.notice.style.color = isError ? "var(--error)" : "var(--warn)";
}

function initWorker() {
  worker = new Worker("./js/worker-preview-cv.js?v=preview-print-" + Date.now());

  worker.onmessage = (event) => {
    const msg = event.data;
    if (msg.type === OMR_MSG.READY) {
      workerReady = true;
      setStatus("Sẵn sàng");
      updateProcessAvailability();
      if (selectedFile && !lastResult) {
        processSelectedFile();
      }
      return;
    }

    if (msg.type === OMR_MSG.ERROR) {
      setStatus("Lỗi worker");
      showNotice(msg.error || "Worker trả về lỗi không xác định.", true);
      updateProcessAvailability();
    }
  };

  worker.onerror = (event) => {
    setStatus("Lỗi worker");
    showNotice(event.message || "Không khởi tạo được worker.", true);
  };

  worker.postMessage({ type: OMR_MSG.INIT });
}

function updateProcessAvailability() {
  els.processBtn.disabled = !(workerReady && selectedFile);
}

async function acceptFile(file) {
  if (!file) return;
  if (!file.type.startsWith("image/")) {
    showNotice("File được chọn không phải ảnh.", true);
    return;
  }

  selectedFile = file;
  lastResult = null;
  els.report.classList.remove("ready");
  els.printBtn.disabled = true;
  els.downloadBtn.disabled = true;
  els.fileMetric.textContent = file.name;
  els.timeMetric.textContent = "-";
  showNotice("");

  const bmp = await createImageBitmap(file);
  els.sizeMetric.textContent = `${bmp.width} x ${bmp.height}px`;
  drawBitmapToCanvas(els.originalCanvas, bmp);
  drawSourcePreviews(bmp);
  bmp.close?.();
  updateProcessAvailability();
  setStatus(workerReady ? "Đã chọn ảnh, đang phân tích..." : "Đang chờ worker");
  if (workerReady) {
    processSelectedFile();
  }
}

function drawBitmapToCanvas(canvas, bitmap) {
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(bitmap, 0, 0);
}

function drawBufferToCanvas(canvas, buffer, width, height) {
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  ctx.putImageData(new ImageData(new Uint8ClampedArray(buffer), width, height), 0, 0);
}

function drawSourcePreviews(bitmap) {
  const stage = downscaleBitmapToImageData(bitmap, 800);
  const gray = rgbaToGray(stage.data);
  const blurred = gaussianBlur5(gray, stage.width, stage.height);
  const threshold = otsuThreshold(blurred);
  const binary = binaryInv(blurred, threshold);

  drawGrayToCanvas(els.grayCanvas, gray, stage.width, stage.height);
  drawGrayToCanvas(els.blurCanvas, blurred, stage.width, stage.height);
  drawGrayToCanvas(els.otsuCanvas, binary, stage.width, stage.height);
}

function drawWarpedPreviews(payload) {
  const width = payload.previewWidth;
  const height = payload.previewHeight;
  const rgba = new Uint8ClampedArray(payload.warpedPreview);
  const gray = rgbaToGray(rgba);
  const blurred = gaussianBlur5(gray, width, height);
  const adaptive = adaptiveThresholdInv(blurred, width, height, 31, 5);

  drawGrayToCanvas(els.warpedGrayCanvas, gray, width, height);
  drawGrayToCanvas(els.warpedBlurCanvas, blurred, width, height);
  drawGrayToCanvas(els.adaptiveCanvas, adaptive, width, height);
  drawBlockOverlay(els.blockOverlayCanvas, rgba, width, height);
  drawDensityOverlay(els.densityOverlayCanvas, rgba, width, height, payload.result);
}

function downscaleBitmapToImageData(bitmap, targetHeight) {
  const scale = Math.min(1, targetHeight / bitmap.height);
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  ctx.drawImage(bitmap, 0, 0, width, height);
  return ctx.getImageData(0, 0, width, height);
}

function rgbaToGray(rgba) {
  const gray = new Uint8ClampedArray(rgba.length / 4);
  for (let i = 0, p = 0; i < gray.length; i += 1, p += 4) {
    gray[i] = Math.round((299 * rgba[p] + 587 * rgba[p + 1] + 114 * rgba[p + 2]) / 1000);
  }
  return gray;
}

function gaussianBlur5(gray, width, height) {
  const kernel = [1, 4, 6, 4, 1];
  const tmp = new Uint16Array(gray.length);
  const out = new Uint8ClampedArray(gray.length);

  for (let y = 0; y < height; y += 1) {
    const row = y * width;
    for (let x = 0; x < width; x += 1) {
      let sum = 0;
      for (let k = -2; k <= 2; k += 1) {
        const xx = Math.max(0, Math.min(width - 1, x + k));
        sum += gray[row + xx] * kernel[k + 2];
      }
      tmp[row + x] = sum;
    }
  }

  for (let y = 0; y < height; y += 1) {
    const row = y * width;
    for (let x = 0; x < width; x += 1) {
      let sum = 0;
      for (let k = -2; k <= 2; k += 1) {
        const yy = Math.max(0, Math.min(height - 1, y + k));
        sum += tmp[yy * width + x] * kernel[k + 2];
      }
      out[row + x] = Math.round(sum / 256);
    }
  }

  return out;
}

function otsuThreshold(gray) {
  const hist = new Uint32Array(256);
  for (let i = 0; i < gray.length; i += 1) hist[gray[i]] += 1;

  const total = gray.length;
  let sum = 0;
  for (let i = 0; i < 256; i += 1) sum += i * hist[i];

  let sumB = 0;
  let wB = 0;
  let maxBetween = -1;
  let threshold = 0;

  for (let t = 0; t < 256; t += 1) {
    wB += hist[t];
    if (wB === 0) continue;
    const wF = total - wB;
    if (wF === 0) break;
    sumB += t * hist[t];
    const mB = sumB / wB;
    const mF = (sum - sumB) / wF;
    const between = wB * wF * (mB - mF) * (mB - mF);
    if (between > maxBetween) {
      maxBetween = between;
      threshold = t;
    }
  }

  return threshold;
}

function binaryInv(gray, threshold) {
  const out = new Uint8ClampedArray(gray.length);
  for (let i = 0; i < gray.length; i += 1) {
    out[i] = gray[i] <= threshold ? 255 : 0;
  }
  return out;
}

function adaptiveThresholdInv(gray, width, height, blockSize, cValue) {
  const radius = Math.floor(blockSize / 2);
  const integralWidth = width + 1;
  const integral = new Uint32Array((width + 1) * (height + 1));
  const out = new Uint8ClampedArray(gray.length);

  for (let y = 1; y <= height; y += 1) {
    let rowSum = 0;
    const srcRow = (y - 1) * width;
    const intRow = y * integralWidth;
    const prevRow = (y - 1) * integralWidth;
    for (let x = 1; x <= width; x += 1) {
      rowSum += gray[srcRow + x - 1];
      integral[intRow + x] = integral[prevRow + x] + rowSum;
    }
  }

  for (let y = 0; y < height; y += 1) {
    const y1 = Math.max(0, y - radius);
    const y2 = Math.min(height - 1, y + radius);
    for (let x = 0; x < width; x += 1) {
      const x1 = Math.max(0, x - radius);
      const x2 = Math.min(width - 1, x + radius);
      const area = (x2 - x1 + 1) * (y2 - y1 + 1);
      const a = y1 * integralWidth + x1;
      const b = y1 * integralWidth + x2 + 1;
      const c = (y2 + 1) * integralWidth + x1;
      const d = (y2 + 1) * integralWidth + x2 + 1;
      const mean = (integral[d] - integral[b] - integral[c] + integral[a]) / area;
      out[y * width + x] = gray[y * width + x] <= mean - cValue ? 255 : 0;
    }
  }

  return out;
}

function drawGrayToCanvas(canvas, gray, width, height) {
  canvas.width = width;
  canvas.height = height;
  const rgba = new Uint8ClampedArray(width * height * 4);
  for (let i = 0, p = 0; i < gray.length; i += 1, p += 4) {
    rgba[p] = gray[i];
    rgba[p + 1] = gray[i];
    rgba[p + 2] = gray[i];
    rgba[p + 3] = 255;
  }
  canvas.getContext("2d").putImageData(new ImageData(rgba, width, height), 0, 0);
}

function drawRgbaToCanvas(canvas, rgba, width, height) {
  canvas.width = width;
  canvas.height = height;
  canvas.getContext("2d").putImageData(new ImageData(new Uint8ClampedArray(rgba), width, height), 0, 0);
}

function drawBlockOverlay(canvas, rgba, width, height) {
  drawRgbaToCanvas(canvas, rgba, width, height);
  const ctx = canvas.getContext("2d");
  ctx.lineWidth = 4;
  ctx.font = "700 34px Segoe UI, Arial";
  ctx.textBaseline = "top";

  PREVIEW_BLOCKS.forEach((block, index) => {
    const isMeta = block.qOffset < 0;
    ctx.strokeStyle = isMeta ? "#2563eb" : "#dc6803";
    ctx.fillStyle = isMeta ? "rgba(37, 99, 235, 0.14)" : "rgba(220, 104, 3, 0.12)";
    ctx.fillRect(block.x1, block.y1, block.x2 - block.x1, block.y2 - block.y1);
    ctx.strokeRect(block.x1, block.y1, block.x2 - block.x1, block.y2 - block.y1);
    ctx.fillStyle = isMeta ? "#1d4ed8" : "#9a3412";
    ctx.fillText(block.name, block.x1 + 8, Math.max(8, block.y1 - 42));

    ctx.fillStyle = index % 2 ? "rgba(17, 24, 39, 0.65)" : "rgba(2, 122, 72, 0.65)";
    eachBlockCenter(block, (cx, cy) => {
      ctx.beginPath();
      ctx.arc(cx, cy, 8, 0, Math.PI * 2);
      ctx.fill();
    });
  });
}

function drawDensityOverlay(canvas, rgba, width, height, result) {
  drawRgbaToCanvas(canvas, rgba, width, height);
  const ctx = canvas.getContext("2d");
  ctx.lineWidth = 4;
  ctx.font = "700 28px Segoe UI, Arial";
  ctx.textBaseline = "middle";

  result.bubbles.forEach((bubble) => {
    const center = getQuestionBubbleCenter(bubble.question, bubble.choice);
    if (!center) return;
    const alpha = Math.max(0.14, Math.min(0.86, bubble.fill_ratio));
    const color = bubble.detected
      ? `rgba(2, 122, 72, ${alpha})`
      : bubble.suspicious
        ? `rgba(245, 158, 11, ${alpha})`
        : `rgba(37, 99, 235, ${Math.max(0.12, alpha * 0.38)})`;

    ctx.fillStyle = color;
    ctx.strokeStyle = bubble.detected ? "#027a48" : bubble.suspicious ? "#b54708" : "rgba(37, 99, 235, 0.55)";
    ctx.beginPath();
    ctx.arc(center.x, center.y, 20, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();

    if (bubble.detected || bubble.suspicious) {
      ctx.fillStyle = "#111827";
      ctx.fillText(bubble.fill_ratio.toFixed(2), center.x + 24, center.y);
    }
  });

  ctx.fillStyle = "rgba(255, 255, 255, 0.88)";
  ctx.fillRect(36, 36, 610, 120);
  ctx.fillStyle = "#101828";
  ctx.font = "700 30px Segoe UI, Arial";
  ctx.fillText("Density overlay: green=selected, amber=suspicious", 56, 76);
  ctx.font = "24px Segoe UI, Arial";
  ctx.fillText(`Answered ${result.answeredCount}/60 | Suspicious ${result.suspiciousCount}`, 56, 118);
}

function eachBlockCenter(block, callback) {
  const cellW = (block.x2 - block.x1) / block.cols;
  const cellH = (block.y2 - block.y1) / block.rows;
  for (let row = 0; row < block.rows; row += 1) {
    for (let col = 0; col < block.cols; col += 1) {
      callback(
        Math.round(block.x1 + (col + 0.5) * cellW),
        Math.round(block.y1 + (row + 0.5) * cellH),
        row,
        col
      );
    }
  }
}

function getQuestionBubbleCenter(question, choice) {
  const block = PREVIEW_BLOCKS.find((item) => item.qOffset >= 0 && question >= item.qOffset + 1 && question <= item.qOffset + 10);
  if (!block) return null;
  const row = question - block.qOffset - 1;
  const cellW = (block.x2 - block.x1) / block.cols;
  const cellH = (block.y2 - block.y1) / block.rows;
  return {
    x: Math.round(block.x1 + (choice + 0.5) * cellW),
    y: Math.round(block.y1 + (row + 0.5) * cellH)
  };
}

function runAnalysis(file) {
  return new Promise((resolve, reject) => {
    const start = performance.now();

    const handler = (event) => {
      const msg = event.data;

      if (msg.type === OMR_MSG.SHEET_RESULT) {
        worker.removeEventListener("message", handler);
        resolve({
          elapsed: performance.now() - start,
          payload: msg.payload
        });
      }

      if (msg.type === OMR_MSG.ERROR) {
        worker.removeEventListener("message", handler);
        reject(new Error(msg.error || "Pipeline lỗi."));
      }
    };

    worker.addEventListener("message", handler);
    worker.postMessage({
      type: OMR_MSG.PROCESS_SHEET,
      payload: {
        width: 0,
        height: 0,
        file,
        groundTruth: null
      }
    });
  });
}

async function processSelectedFile() {
  if (!selectedFile || !workerReady || isProcessing) return;

  try {
    isProcessing = true;
    setStatus("Đang phân tích...");
    showNotice("");
    els.processBtn.disabled = true;
    els.printBtn.disabled = true;
    els.downloadBtn.disabled = true;

    const { elapsed, payload } = await runAnalysis(selectedFile);
    lastResult = { elapsed, payload, file: selectedFile };

    if (payload.warpedPreview) {
      drawBufferToCanvas(els.warpedCanvas, payload.warpedPreview, payload.previewWidth, payload.previewHeight);
      drawWarpedPreviews(payload);
    } else {
      markCanvasUnavailable(els.warpedCanvas, "Không có ảnh warp");
      markCanvasUnavailable(els.warpedGrayCanvas, "Không có ảnh warp");
      markCanvasUnavailable(els.warpedBlurCanvas, "Không có ảnh warp");
      markCanvasUnavailable(els.adaptiveCanvas, "Không có ảnh warp");
      markCanvasUnavailable(els.blockOverlayCanvas, "Không có ảnh warp");
      markCanvasUnavailable(els.densityOverlayCanvas, "Không có ảnh warp");
    }

    if (payload.preview) {
      drawBufferToCanvas(els.annotatedCanvas, payload.preview, payload.previewWidth, payload.previewHeight);
    } else {
      markCanvasUnavailable(els.annotatedCanvas, "Không có ảnh annotated");
    }

    drawHeatmap(els.heatmapCanvas, payload.result);
    renderSummary(payload, elapsed);
    renderAnswerTable(payload.result);
    renderLog(payload, elapsed);

    els.report.classList.add("ready");
    els.reportMeta.textContent = `${selectedFile.name} | ${new Date().toLocaleString("vi-VN")} | Engine: Traditional CV + WASM`;
    els.resultBadge.textContent = payload.result.status === 0 ? "Phân tích xong" : `Status ${payload.result.status}`;
    els.resultBadge.className = payload.result.status === 0 ? "badge" : "badge warn";
    els.timeMetric.textContent = `${elapsed.toFixed(1)}ms`;
    setStatus("Hoàn tất");
    els.printBtn.disabled = false;
    els.downloadBtn.disabled = false;
  } catch (error) {
    setStatus("Lỗi phân tích");
    showNotice(error.message, true);
  } finally {
    isProcessing = false;
    updateProcessAvailability();
  }
}

function markCanvasUnavailable(canvas, text) {
  canvas.width = 900;
  canvas.height = 500;
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "#667085";
  ctx.font = "28px Segoe UI, Arial";
  ctx.textAlign = "center";
  ctx.fillText(text, canvas.width / 2, canvas.height / 2);
}

function drawHeatmap(canvas, result) {
  const cellW = 62;
  const cellH = 26;
  const left = 66;
  const top = 42;
  const width = left + cellW * 5 + 26;
  const height = top + cellH * 60 + 36;

  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, width, height);

  ctx.fillStyle = "#101828";
  ctx.font = "700 18px Segoe UI, Arial";
  ctx.fillText("Bubble density heatmap", 18, 24);

  ctx.font = "700 12px Segoe UI, Arial";
  ANSWER_LABELS.forEach((label, col) => {
    ctx.fillStyle = "#475467";
    ctx.textAlign = "center";
    ctx.fillText(label, left + col * cellW + cellW / 2, top - 12);
  });

  result.answers.forEach((answer, row) => {
    const y = top + row * cellH;
    ctx.fillStyle = "#667085";
    ctx.textAlign = "right";
    ctx.font = "12px Segoe UI, Arial";
    ctx.fillText(`Q${String(row + 1).padStart(2, "0")}`, left - 10, y + 17);

    for (let col = 0; col < 5; col += 1) {
      const bubble = result.bubbles.find((item) => item.question === row + 1 && item.choice === col);
      const ratio = bubble ? bubble.fill_ratio : 0;
      const selected = (answer.mask & (1 << col)) !== 0;
      ctx.fillStyle = densityColor(ratio, selected);
      ctx.fillRect(left + col * cellW, y, cellW - 3, cellH - 3);

      ctx.strokeStyle = selected ? "#027a48" : "#d0d5dd";
      ctx.lineWidth = selected ? 2 : 1;
      ctx.strokeRect(left + col * cellW + 0.5, y + 0.5, cellW - 4, cellH - 4);

      ctx.fillStyle = ratio > 0.52 ? "#ffffff" : "#101828";
      ctx.textAlign = "center";
      ctx.font = selected ? "700 11px Segoe UI, Arial" : "11px Segoe UI, Arial";
      ctx.fillText(ratio.toFixed(2), left + col * cellW + cellW / 2 - 1, y + 17);
    }
  });
}

function densityColor(ratio, selected) {
  const clamped = Math.max(0, Math.min(1, ratio));
  if (selected) {
    const g = Math.round(125 - clamped * 75);
    return `rgb(2, ${g}, 72)`;
  }
  const shade = Math.round(250 - clamped * 170);
  return `rgb(${shade}, ${Math.round(shade + 4)}, ${Math.round(shade + 8)})`;
}

function renderSummary(payload, elapsed) {
  const result = payload.result;
  const perf = payload.perf || {};
  const rows = [
    ["File", selectedFile.name],
    ["Ảnh đầu vào", `${payload.width} x ${payload.height}px`],
    ["Preview chuẩn hóa", `${payload.previewWidth} x ${payload.previewHeight}px`],
    ["MSSV", result.mssv || "Không hợp lệ / trống"],
    ["Mã đề", result.examCode || "Không hợp lệ / trống"],
    ["Câu đã nhận dạng", `${result.answeredCount}/60`],
    ["Câu nghi ngờ", result.suspiciousCount],
    ["Câu multi-mark", result.multiMarkCount],
    ["C++ OMR", perf.cpp_ms !== undefined ? `${perf.cpp_ms.toFixed(1)}ms` : "-"],
    ["End-to-end", `${elapsed.toFixed(1)}ms`],
    ["WASM variant", perf.wasm_variant || "baseline"],
    ["WASM heap", perf.wasm_heap_after ? `${(perf.wasm_heap_after / 1048576).toFixed(1)}MB` : "-"]
  ];

  els.summaryBody.innerHTML = rows.map(([label, value]) => (
    `<tr><td><strong>${escapeHtml(label)}</strong></td><td>${escapeHtml(String(value))}</td></tr>`
  )).join("");
}

function renderAnswerTable(result) {
  const rows = result.answers.map((answer) => {
    const selected = answer.selected.length ? answer.selected.join("+") : "-";
    const badge = answer.suspicious ? `<span class="badge warn">Nghi ngờ</span>` : `<span class="badge">OK</span>`;
    return `<tr><td>Q${String(answer.q).padStart(2, "0")}</td><td>${escapeHtml(selected)}</td><td>${badge}</td></tr>`;
  }).join("");

  els.answerTableWrap.innerHTML = `
    <table class="answer-table">
      <thead>
        <tr><th>Câu</th><th>Đáp án đọc được</th><th>Trạng thái</th></tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

function renderLog(payload, elapsed) {
  const perf = payload.perf || {};
  const lines = [];
  lines.push(`[JS] File: ${selectedFile.name}`);
  lines.push(`[JS] Input: ${payload.width}x${payload.height}`);
  lines.push(`[JS] E2E: ${elapsed.toFixed(1)}ms`);
  lines.push(`[WASM] Variant: ${perf.wasm_variant || "baseline"}`);
  lines.push(`[WASM] Heap before: ${formatBytes(perf.wasm_heap_before)}`);
  lines.push(`[WASM] Heap after: ${formatBytes(perf.wasm_heap_after)}`);
  lines.push(`[C++] OMR stage: ${perf.cpp_ms !== undefined ? perf.cpp_ms.toFixed(1) + "ms" : "-"}`);
  lines.push(`[RESULT] MSSV=${payload.result.mssv || "-"} ExamCode=${payload.result.examCode || "-"} Answered=${payload.result.answeredCount}/60`);

  if (perf.cpp_logs && perf.cpp_logs.length) {
    lines.push("");
    lines.push("C++ logs:");
    perf.cpp_logs.forEach((item) => {
      lines.push(`[T+${Math.round(item.ts)}ms] ${String(item.msg).trim()}`);
    });
  }

  els.logBox.textContent = lines.join("\n");
}

function formatBytes(value) {
  if (!Number.isFinite(value) || value <= 0) return "-";
  return `${(value / 1048576).toFixed(1)}MB`;
}

function escapeHtml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function downloadCanvas(canvasId) {
  const canvas = document.getElementById(canvasId);
  if (!canvas || !canvas.width || !canvas.height) return;

  const filenameBase = selectedFile ? selectedFile.name.replace(/\.[^.]+$/, "") : "omr-preview";
  const link = document.createElement("a");
  link.href = canvas.toDataURL("image/png");
  link.download = `${filenameBase}-${canvasId}.png`;
  link.click();
}

function downloadPreviewSet() {
  [
    "originalCanvas",
    "grayCanvas",
    "blurCanvas",
    "otsuCanvas",
    "warpedCanvas",
    "warpedGrayCanvas",
    "warpedBlurCanvas",
    "adaptiveCanvas",
    "blockOverlayCanvas",
    "annotatedCanvas",
    "densityOverlayCanvas",
    "heatmapCanvas"
  ].forEach((canvasId, index) => {
    window.setTimeout(() => downloadCanvas(canvasId), index * 180);
  });
}

function wireEvents() {
  els.input.addEventListener("change", (event) => acceptFile(event.target.files?.[0]));
  els.dropInput.addEventListener("change", (event) => acceptFile(event.target.files?.[0]));
  els.processBtn.addEventListener("click", processSelectedFile);
  els.printBtn.addEventListener("click", () => window.print());
  els.downloadBtn.addEventListener("click", downloadPreviewSet);

  document.querySelectorAll("[data-download]").forEach((button) => {
    button.addEventListener("click", () => downloadCanvas(button.dataset.download));
  });

  ["dragenter", "dragover"].forEach((name) => {
    els.dropzone.addEventListener(name, (event) => {
      event.preventDefault();
      els.dropzone.classList.add("dragging");
    });
  });

  ["dragleave", "drop"].forEach((name) => {
    els.dropzone.addEventListener(name, (event) => {
      event.preventDefault();
      els.dropzone.classList.remove("dragging");
    });
  });

  els.dropzone.addEventListener("drop", (event) => {
    const file = event.dataTransfer.files?.[0];
    acceptFile(file);
  });
}

wireEvents();
initWorker();
