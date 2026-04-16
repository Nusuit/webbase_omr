(() => {
  const OMR_MSG = {
    INIT: "omr/init",
    READY: "omr/ready",
    PROCESS_SHEET: "omr/process-sheet",
    SHEET_RESULT: "omr/sheet-result",
    ERROR: "omr/error"
  };

  const SHEET_W = 1700;
  const SHEET_H = 2400;
  const OPTIONS = ["A", "B", "C", "D", "E"];

  const BLOCKS = [
    { start: 1, x1: 880, y1: 860, x2: 1183, y2: 1530, rows: 10, cols: 5 },
    { start: 11, x1: 1249, y1: 856, x2: 1563, y2: 1527, rows: 10, cols: 5 },
    { start: 21, x1: 140, y1: 1604, x2: 447, y2: 2311, rows: 10, cols: 5 },
    { start: 31, x1: 515, y1: 1597, x2: 819, y2: 2303, rows: 10, cols: 5 },
    { start: 41, x1: 882, y1: 1593, x2: 1194, y2: 2311, rows: 10, cols: 5 },
    { start: 51, x1: 1251, y1: 1585, x2: 1568, y2: 2292, rows: 10, cols: 5 }
  ];

  const ui = {
    projectsView: document.getElementById("projectsView"),
    scanView: document.getElementById("scanView"),
    resultView: document.getElementById("resultView"),

    projectNameInput: document.getElementById("projectNameInput"),
    createProjectBtn: document.getElementById("createProjectBtn"),
    refreshProjectsBtn: document.getElementById("refreshProjectsBtn"),
    projectList: document.getElementById("projectList"),

    backToProjectsBtn: document.getElementById("backToProjectsBtn"),
    activeProjectTitle: document.getElementById("activeProjectTitle"),
    goResultBtn: document.getElementById("goResultBtn"),
    exportBtn: document.getElementById("exportBtn"),
    scanStatus: document.getElementById("scanStatus"),

    addStudentBtn: document.getElementById("addStudentBtn"),
    addKeyBtn: document.getElementById("addKeyBtn"),
    clearStudentBtn: document.getElementById("clearStudentBtn"),
    clearKeyBtn: document.getElementById("clearKeyBtn"),
    studentCountText: document.getElementById("studentCountText"),
    keyCountText: document.getElementById("keyCountText"),
    studentThumbList: document.getElementById("studentThumbList"),
    keyThumbList: document.getElementById("keyThumbList"),
    startGradingBtn: document.getElementById("startGradingBtn"),

    progressText: document.getElementById("progressText"),
    progressCount: document.getElementById("progressCount"),
    progressFill: document.getElementById("progressFill"),

    uploadInput: document.getElementById("uploadInput"),

    resultSummary: document.getElementById("resultSummary"),
    backToScanBtn: document.getElementById("backToScanBtn"),
    resultTabStudentBtn: document.getElementById("resultTabStudentBtn"),
    resultTabKeyBtn: document.getElementById("resultTabKeyBtn"),
    resultTabHint: document.getElementById("resultTabHint"),
    resultStudentPane: document.getElementById("resultStudentPane"),
    resultKeyPane: document.getElementById("resultKeyPane"),
    studentList: document.getElementById("studentList"),
    keyList: document.getElementById("keyList"),
    suspiciousList: document.getElementById("suspiciousList"),
    reviewModal2: document.getElementById("reviewModal2"),
    reviewTitle: document.getElementById("reviewTitle"),
    reviewMeta: document.getElementById("reviewMeta"),
    reviewWarn: document.getElementById("reviewWarn"),
    reviewStudentLabel: document.getElementById("reviewStudentLabel"),
    reviewKeyLabel: document.getElementById("reviewKeyLabel"),
    reviewStudentImg: document.getElementById("reviewStudentImg"),
    reviewKeyImg: document.getElementById("reviewKeyImg"),
    reviewQuestionList: document.getElementById("reviewQuestionList"),
    closeReviewBtn: document.getElementById("closeReviewBtn"),
    editIdentityBtn: document.getElementById("editIdentityBtn"),
    reviewConfirmBtn: document.getElementById("reviewConfirmBtn"),

    addModeModal: document.getElementById("addModeModal"),
    addModeTitle: document.getElementById("addModeTitle"),
    addByCaptureBtn: document.getElementById("addByCaptureBtn"),
    addByUploadBtn: document.getElementById("addByUploadBtn"),
    cancelAddModeBtn: document.getElementById("cancelAddModeBtn"),

    captureModal: document.getElementById("captureModal"),
    captureVideo: document.getElementById("captureVideo"),
    takePhotoBtn: document.getElementById("takePhotoBtn"),
    cancelCaptureBtn: document.getElementById("cancelCaptureBtn"),

    reviewModal: document.getElementById("reviewModal"),
    reviewCanvas: document.getElementById("reviewCanvas"),
    rotateLeftBtn: document.getElementById("rotateLeftBtn"),
    rotateRightBtn: document.getElementById("rotateRightBtn"),
    retakeBtn: document.getElementById("retakeBtn"),
    useImageBtn: document.getElementById("useImageBtn"),

    editModal: document.getElementById("editModal"),
    editMeta: document.getElementById("editMeta"),
    editQuestionGrid: document.getElementById("editQuestionGrid"),
    closeEditBtn: document.getElementById("closeEditBtn"),

    exportModal: document.getElementById("exportModal"),
    exportFormatSelect: document.getElementById("exportFormatSelect"),
    exportScopeSelect: document.getElementById("exportScopeSelect"),
    confirmExportBtn: document.getElementById("confirmExportBtn"),
    cancelExportBtn: document.getElementById("cancelExportBtn"),
    closeExportBtn: document.getElementById("closeExportBtn")
  };

  const reviewCtx = ui.reviewCanvas.getContext("2d", { willReadFrequently: true });

  const worker = new Worker("./js/worker.js?v=20260307-10");
  const store = new window.UploadStore();

  const state = {
    workerReady: false,
    gradingBusy: false,
    activeProject: null,

    pendingSheetResolver: null,

    gradingTotal: 0,
    gradingDone: 0,

    pendingAddKind: "student",

    captureStream: null,

    review: {
      bitmap: null,
      blob: null,
      name: null,
      kind: "student",
      rotation: 0,
      source: "upload"
    },

    editingSuspiciousId: null,
    editingCommittedId: null,
    editingKeyCode: null,
    editingQuestion: null,
    editingMap: {},
    reviewMode: "",
    reviewDirty: false,
    reviewedQuestions: new Set(),
    resultTab: "student",
    keyResult: null,
    keyPreviewDataUrl: "",
    keyResultsByCode: {},
    keyPreviewByCode: {},
    keySourceByCode: {},
    keyBlobByCode: {},
    keyProcessedByCode: {},
    keyWarpedByCode: {},
    keyOverrides: {},
    defaultKeyCode: null,
    keyCacheBuilt: false
  };

  // ── Performance instrumentation (research comparison) ────────────────────
  window.__perfLog = [];
  window.__perfSummary = function () {
    const log = window.__perfLog;
    if (!log.length) { console.log("[PERF] No data yet"); return; }
    const n = log.length;
    const avg = k => (log.reduce((s, e) => s + (e[k] ?? 0), 0) / n).toFixed(1);
    const mn  = k => Math.min(...log.map(e => e[k] ?? Infinity)).toFixed(1);
    const mx  = k => Math.max(...log.map(e => e[k] ?? -Infinity)).toFixed(1);
    const out = { branch: log[0]?.branch, n };
    ["e2e_ms","yolo_ms","cpp_ms","worker_total_ms","overhead_ms"].forEach(k => {
      out[k] = { avg: avg(k), min: mn(k), max: mx(k) };
    });
    console.table(out);
    return log;
  };
  // ── End performance instrumentation ──────────────────────────────────────

  // Clean up legacy modal-tab DOM if browser serves an older cached template.
  function removeLegacyReviewTabs() {
    ["reviewTabStudentBtn", "reviewTabKeyBtn", "reviewStudentPane", "reviewKeyPane"].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.remove();
    });
    if (ui.reviewModal2) {
      ui.reviewModal2.querySelectorAll("[data-review-tab], .review-tabs, .review-preview-tabs").forEach((el) => {
        el.remove();
      });
    }
  }

  removeLegacyReviewTabs();

  function setStatus(text) {
    ui.scanStatus.textContent = text;
  }

  function downloadBlob(blob, filename) {
    const a = document.createElement("a");
    const url = URL.createObjectURL(blob);
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  function openExportModal() {
    if (!state.activeProject) return;
    ui.exportFormatSelect.value = "json";
    ui.exportScopeSelect.value = "result";
    ui.exportModal.classList.add("show");
  }

  function closeExportModal() {
    ui.exportModal.classList.remove("show");
  }

  function normalizeExamCode(value) {
    const raw = String(value ?? "").trim();
    if (!raw) return "";
    const digits = raw.replace(/\D/g, "");
    if (!digits) return raw.toUpperCase();
    return digits.slice(-3).padStart(3, "0");
  }

  function normalizeKeyOverrides(overrides) {
    const out = {};
    for (const [code, value] of Object.entries(overrides || {})) {
      const normalized = normalizeExamCode(code);
      if (!normalized) continue;
      out[normalized] = value;
    }
    return out;
  }

  function toSafeStudentId(row, index) {
    const id = String(row?.mssv || "").trim();
    if (id) return id;
    const name = String(row?.sourceName || "").trim();
    return name || `SV_${index + 1}`;
  }

  function buildAnswerMap(result) {
    const map = {};
    for (let q = 1; q <= 60; q += 1) map[q] = "-";
    for (const ans of result?.answers || []) {
      map[ans.q] = formatOpts(ans);
    }
    return map;
  }

  function toSummaryRow(row, index) {
    const total = Number.isFinite(row.totalCount) ? row.totalCount : 60;
    const correct = Number.isFinite(row.correctCount) ? row.correctCount : 0;
    const safeTotal = Math.max(1, total);
    const wrong = Math.max(0, total - correct);
    const answered = Number.isFinite(row.answeredCount) ? row.answeredCount : 0;
    const score = Number.isFinite(row.score10) ? Number(row.score10.toFixed(2)) : Number(((correct / safeTotal) * 10).toFixed(2));
    return {
      stt: index + 1,
      studentId: toSafeStudentId(row, index),
      examCode: row.examCode || "",
      score10: score,
      correct,
      wrong,
      answered,
      sourceName: row.sourceName || "",
      ts: row.ts ? new Date(row.ts).toLocaleString() : "",
      suspiciousCount: row.suspiciousCount || 0
    };
  }

  function csvEscape(value) {
    const text = value == null ? "" : String(value);
    if (/[",\r\n]/.test(text)) return `"${text.replace(/"/g, "\"\"")}"`;
    return text;
  }

  function buildCsv(rows) {
    return rows.map((row) => row.map((cell) => csvEscape(cell)).join(",")).join("\r\n");
  }

  async function exportProjectData(options = { format: "json", scope: "result" }) {
    if (!state.activeProject) return;
    const [committedAll, suspicious] = await Promise.all([
      store.listCommittedScans(state.activeProject.id),
      store.listSuspicious(state.activeProject.id)
    ]);

    const committed = committedAll.filter((row) => (row.suspiciousCount || 0) === 0);
    if (committed.length === 0) {
      setStatus("Chưa có bài đã lưu để export");
      alert("Chưa có bài đã lưu để export.");
      return;
    }

    const summaries = committed.map((row, idx) => toSummaryRow(row, idx));
    const studentsWithAnswers = committed.map((row, idx) => ({
      studentId: toSafeStudentId(row, idx),
      examCode: row.examCode || "",
      answers: buildAnswerMap(row.payload),
      summary: summaries[idx]
    }));

    const safeName = (state.activeProject.name || "project").replace(/[^\w.-]+/g, "_");
    const stamp = Date.now();

    if (options.format === "json") {
      const data =
        options.scope === "full"
          ? {
              exportedAt: new Date().toISOString(),
              exportType: "full_answers",
              project: {
                id: state.activeProject.id,
                name: state.activeProject.name,
                createdAt: state.activeProject.ts
              },
              committedCount: committed.length,
              reviewCount: suspicious.length,
              students: studentsWithAnswers,
              summary: summaries
            }
          : {
              exportedAt: new Date().toISOString(),
              exportType: "result_only",
              project: {
                id: state.activeProject.id,
                name: state.activeProject.name,
                createdAt: state.activeProject.ts
              },
              committedCount: committed.length,
              reviewCount: suspicious.length,
              summary: summaries
            };

      const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
      downloadBlob(blob, `${safeName}_omr_${options.scope}_${stamp}.json`);
      setStatus(`Đã export JSON (${options.scope === "full" ? "toàn bộ đáp án" : "kết quả"})`);
      return;
    }

    if (options.scope === "full") {
      const rows = [];
      rows.push(["STT", ...studentsWithAnswers.map((x) => x.studentId)]);
      for (let q = 1; q <= 60; q += 1) {
        rows.push([q, ...studentsWithAnswers.map((x) => x.answers[q] || "-")]);
      }
      rows.push([]);
      rows.push(["TONG HOP KET QUA"]);
      rows.push(["MSSV", "MA_DE", "DIEM", "SAI", "DUNG", "DA_TO", "NGUON", "THOI_GIAN"]);
      for (const s of summaries) {
        rows.push([s.studentId, s.examCode, s.score10, s.wrong, s.correct, s.answered, s.sourceName, s.ts]);
      }
      const csv = buildCsv(rows);
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
      downloadBlob(blob, `${safeName}_omr_full_${stamp}.csv`);
      setStatus("Đã export CSV (toàn bộ đáp án + tổng hợp)");
      return;
    }

    const rows = [["MSSV", "DIEM", "SAI", "DUNG", "DA_TO", "MA_DE", "NGUON", "THOI_GIAN"]];
    for (const s of summaries) {
      rows.push([s.studentId, s.score10, s.wrong, s.correct, s.answered, s.examCode, s.sourceName, s.ts]);
    }
    const csv = buildCsv(rows);
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    downloadBlob(blob, `${safeName}_omr_result_${stamp}.csv`);
    setStatus("Đã export CSV (chỉ kết quả)");
  }

  function setResultTab(tab) {
    state.resultTab = tab === "key" ? "key" : "student";
    const showStudent = state.resultTab === "student";
    ui.resultTabStudentBtn.classList.toggle("active", showStudent);
    ui.resultTabKeyBtn.classList.toggle("active", !showStudent);
    ui.resultStudentPane.style.display = showStudent ? "" : "none";
    ui.resultKeyPane.style.display = showStudent ? "none" : "";
    ui.resultTabHint.textContent = showStudent
      ? "Không suspicious hoặc đã xác nhận/sửa xong"
      : "Đáp án đã đọc theo từng mã đề (nhấn vào để xem preview/chỉnh tay)";
  }

  function showView(name) {
    ui.projectsView.classList.toggle("active", name === "projects");
    ui.scanView.classList.toggle("active", name === "scan");
    ui.resultView.classList.toggle("active", name === "result");
  }

  function setResultMetrics() {}

  function updateProgressUI() {
    if (state.gradingTotal <= 0) {
      ui.progressText.textContent = "Chưa chấm";
      ui.progressCount.textContent = "0/0";
      ui.progressFill.style.width = "0%";
      return;
    }

    const pct = Math.round((state.gradingDone / state.gradingTotal) * 100);
    ui.progressText.textContent = state.gradingDone < state.gradingTotal ? "Đang chấm bài..." : "Hoàn tất chấm";
    ui.progressCount.textContent = `${state.gradingDone}/${state.gradingTotal}`;
    ui.progressFill.style.width = `${pct}%`;
  }

  function dataUrlFromBlob(blob) {
    return new Promise((resolve, reject) => {
      const fr = new FileReader();
      fr.onload = () => resolve(fr.result);
      fr.onerror = () => reject(fr.error);
      fr.readAsDataURL(blob);
    });
  }

  async function createBitmapFromBlob(blob) {
    try {
      return await createImageBitmap(blob, { imageOrientation: "from-image" });
    } catch (_) {
      return await createImageBitmap(blob);
    }
  }

  function resetKeyCache() {
    state.keyResult = null;
    state.keyPreviewDataUrl = "";
    state.keyResultsByCode = {};
    state.keyPreviewByCode = {};
    state.keySourceByCode = {};
    state.keyBlobByCode = {};
    state.keyProcessedByCode = {};
    state.keyWarpedByCode = {};
    state.defaultKeyCode = null;
    state.keyCacheBuilt = false;
  }

  function answersToOverrideMap(result) {
    const out = {};
    for (const ans of result?.answers || []) {
      out[ans.q] = Array.from(answerToSet(ans));
    }
    return out;
  }

  function applyKeyOverrideToResult(result, examCode) {
    const code = normalizeExamCode(examCode);
    if (!result || !code) return result;
    const override = state.keyOverrides?.[code];
    if (!override) return result;

    const updated = structuredClone(result);
    updated.answers = (updated.answers || []).map((ans) => {
      const selected = Array.isArray(override[ans.q]) ? override[ans.q] : ans.selected || [];
      const normalized = selected
        .map((v) => String(v).trim().toUpperCase())
        .filter((v) => OPTIONS.includes(v));
      const mask = normalized.reduce((acc, cur) => {
        const idx = OPTIONS.indexOf(cur);
        return idx >= 0 ? acc | (1 << idx) : acc;
      }, 0);
      return { ...ans, selected: normalized, mask, suspicious: false };
    });
    updated.answeredCount = updated.answers.filter((a) => a.mask !== 0).length;
    updated.suspiciousCount = 0;
    return updated;
  }

  async function persistKeyOverrides() {
    if (!state.activeProject) return;
    try {
      await store.updateProject(state.activeProject.id, {
        keyOverrides: state.keyOverrides
      });
    } catch (err) {
      console.error("Failed to persist key overrides:", err);
    }
  }

  async function buildKeyPreviewAssets(processPayload, sourceBlob) {
    const processedBlob = processPayload?.preview
      ? await imageDataBlob(processPayload.preview, processPayload.previewWidth, processPayload.previewHeight)
      : null;
    const warpedBlob = processPayload?.warpedPreview
      ? await imageDataBlob(processPayload.warpedPreview, processPayload.previewWidth, processPayload.previewHeight)
      : null;
    const previewBlob = warpedBlob || processedBlob || sourceBlob || null;
    const previewUrl = previewBlob ? await dataUrlFromBlob(previewBlob) : "";
    return { previewUrl, processedBlob, warpedBlob };
  }

  function setKeyCacheEntry(
    examCode,
    keyResult,
    keyPreviewDataUrl,
    sourceName = "",
    sourceBlob = null,
    processedBlob = null,
    warpedBlob = null
  ) {
    if (!keyResult) return;
    const code = normalizeExamCode(examCode);
    const normalizedKeyResult = { ...keyResult, examCode: code || keyResult.examCode || "" };

    if (!state.keyResult) state.keyResult = normalizedKeyResult;
    if (!state.keyPreviewDataUrl && keyPreviewDataUrl) state.keyPreviewDataUrl = keyPreviewDataUrl;

    if (!code) return;
    if (!state.keyResultsByCode[code]) {
      state.keyResultsByCode[code] = applyKeyOverrideToResult(normalizedKeyResult, code);
      if (keyPreviewDataUrl) state.keyPreviewByCode[code] = keyPreviewDataUrl;
      if (sourceName) state.keySourceByCode[code] = sourceName;
      if (sourceBlob) state.keyBlobByCode[code] = sourceBlob;
      if (processedBlob) state.keyProcessedByCode[code] = processedBlob;
      if (warpedBlob) state.keyWarpedByCode[code] = warpedBlob;
      if (!state.defaultKeyCode) state.defaultKeyCode = code;
    }
  }

  function getDefaultKeyResult() {
    if (state.defaultKeyCode && state.keyResultsByCode[state.defaultKeyCode]) {
      return state.keyResultsByCode[state.defaultKeyCode];
    }
    return state.keyResult;
  }

  function getKeyResultForExamCode(examCode) {
    const code = normalizeExamCode(examCode);
    if (code && state.keyResultsByCode[code]) return state.keyResultsByCode[code];
    return getDefaultKeyResult();
  }

  function getMatchedKeyResult(examCode) {
    const code = normalizeExamCode(examCode);
    if (code && state.keyResultsByCode[code]) return state.keyResultsByCode[code];
    return null;
  }

  function getKeyPreviewForExamCode(examCode) {
    const code = normalizeExamCode(examCode);
    if (code && state.keyPreviewByCode[code]) return state.keyPreviewByCode[code];
    return state.keyPreviewDataUrl || "";
  }

  async function ensureKeyCache(keys) {
    if (!state.workerReady) return;
    if (state.keyCacheBuilt) return;
    resetKeyCache();

    for (const keyRow of keys) {
      const payload = await runSheetProcess(keyRow.blob);
      const assets = await buildKeyPreviewAssets(payload, keyRow.blob);
      setKeyCacheEntry(
        payload.result?.examCode,
        payload.result,
        assets.previewUrl,
        keyRow.name,
        keyRow.blob,
        assets.processedBlob,
        assets.warpedBlob
      );
    }

    state.keyCacheBuilt = true;
  }

  function imageDataBlob(buffer, width, height) {
    return new Promise((resolve) => {
      const canvas = document.createElement("canvas");
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext("2d");
      const out = new Uint8ClampedArray(buffer);
      ctx.putImageData(new ImageData(out, width, height), 0, 0);
      canvas.toBlob((blob) => resolve(blob), "image/jpeg", 0.92);
    });
  }

  function questionCenter(q, option) {
    const block = BLOCKS.find((b) => q >= b.start && q < b.start + 10);
    if (!block) return null;

    const row = q - block.start;
    const col = OPTIONS.indexOf(option);
    if (col < 0) return null;

    // Use integer division to exactly match C++ ScaleCell (which works at 1700×2400)
    const cellW = Math.floor((block.x2 - block.x1) / block.cols);
    const cellH = Math.floor((block.y2 - block.y1) / block.rows);

    return {
      x: block.x1 + col * cellW + Math.floor(cellW / 2),
      y: block.y1 + row * cellH + Math.floor(cellH / 2),
      r: Math.max(8, Math.min(cellW, cellH) * 0.38)
    };
  }

  async function buildAnnotatedPreview(row, keyAnswers) {
    // Background priority:
    //   1. warpedBlob  – color, perspective-normalized 1700×2400  ← ideal
    //   2. processedBlob – binary, perspective-normalized 1700×2400 ← circles still align
    //   3. sourceBlob  – original camera photo (AVOID: circles won't align)
    const useWarped    = !!row.warpedBlob;
    const useProcessed = !useWarped && !!row.processedBlob;
    const srcBlob = useWarped
      ? row.warpedBlob
      : (useProcessed ? row.processedBlob : (row.sourceBlob || row.previewBlob));
    if (!srcBlob) return "";

    const bmp = await createBitmapFromBlob(srcBlob);
    const canvas = document.createElement("canvas");
    canvas.width = SHEET_W;
    canvas.height = SHEET_H;
    const ctx = canvas.getContext("2d");

    if (useWarped) {
      // Color warped image is already 1700×2400 — draw 1:1
      ctx.drawImage(bmp, 0, 0, SHEET_W, SHEET_H);
    } else if (useProcessed) {
      // Binary normalized image (BINARY_INV: paper=black, filled=white).
      // Invert so paper becomes white, filled marks become dark → readable.
      ctx.drawImage(bmp, 0, 0, SHEET_W, SHEET_H);
      const imgData = ctx.getImageData(0, 0, SHEET_W, SHEET_H);
      const d = imgData.data;
      for (let i = 0; i < d.length; i += 4) {
        d[i]     = 255 - d[i];
        d[i + 1] = 255 - d[i + 1];
        d[i + 2] = 255 - d[i + 2];
      }
      ctx.putImageData(imgData, 0, 0);
    } else {
      // Last resort: original photo cover-scaled (circles WILL be misaligned)
      const sc = Math.max(SHEET_W / bmp.width, SHEET_H / bmp.height);
      const ox = (SHEET_W - bmp.width  * sc) / 2;
      const oy = (SHEET_H - bmp.height * sc) / 2;
      ctx.fillStyle = "#fff";
      ctx.fillRect(0, 0, SHEET_W, SHEET_H);
      ctx.drawImage(bmp, ox, oy, bmp.width * sc, bmp.height * sc);
    }

    // Build student answer map: q → { opts: Set, suspicious: bool }
    const studentMap = new Map();
    for (const ans of row.payload?.answers || []) {
      studentMap.set(ans.q, { opts: answerToSet(ans), suspicious: !!ans.suspicious });
    }

    // Build key map: q → Set  (only if key is provided)
    const keyMap = new Map();
    const hasKey = Array.isArray(keyAnswers) && keyAnswers.length > 0;
    if (hasKey) {
      for (const ans of keyAnswers) {
        keyMap.set(ans.q, answerToSet(ans));
      }
    }

    ctx.lineJoin = "round";
    ctx.lineCap = "round";

    for (let q = 1; q <= 60; q += 1) {
      const student = studentMap.get(q);
      const keySet  = keyMap.get(q);    // undefined if no key
      const isSusp  = student?.suspicious;

      for (const opt of OPTIONS) {
        const c = questionCenter(q, opt);
        if (!c) continue;

        const studentFilled = student?.opts.has(opt) ?? false;
        const isCorrectOpt  = hasKey ? (keySet?.has(opt) ?? false) : null;

        if (studentFilled && isCorrectOpt === true) {
          // ── Correct: green fill + green solid stroke (Android paintCorrectFill + paintCorrect)
          ctx.beginPath();
          ctx.arc(c.x, c.y, c.r, 0, Math.PI * 2);
          ctx.fillStyle = "rgba(76,175,80,0.25)";
          ctx.fill();

          ctx.beginPath();
          ctx.arc(c.x, c.y, c.r, 0, Math.PI * 2);
          ctx.strokeStyle = "#4CAF50";
          ctx.lineWidth = 3;
          ctx.setLineDash([]);
          ctx.stroke();

        } else if (studentFilled && isCorrectOpt === false) {
          // ── Wrong: red stroke + cross (Android paintWrong + drawCross)
          ctx.beginPath();
          ctx.arc(c.x, c.y, c.r, 0, Math.PI * 2);
          ctx.strokeStyle = "#F44336";
          ctx.lineWidth = 3;
          ctx.setLineDash([]);
          ctx.stroke();

          const arm = c.r * 0.55;
          ctx.strokeStyle = "#F44336";
          ctx.lineWidth = 2.5;
          ctx.beginPath();
          ctx.moveTo(c.x - arm, c.y - arm);
          ctx.lineTo(c.x + arm, c.y + arm);
          ctx.stroke();
          ctx.beginPath();
          ctx.moveTo(c.x + arm, c.y - arm);
          ctx.lineTo(c.x - arm, c.y + arm);
          ctx.stroke();

        } else if (!studentFilled && isCorrectOpt === true) {
          // ── Missed correct: green dashed stroke (Android paintMissed)
          ctx.beginPath();
          ctx.arc(c.x, c.y, c.r, 0, Math.PI * 2);
          ctx.strokeStyle = "#4CAF50";
          ctx.lineWidth = 2.5;
          ctx.setLineDash([6, 5]);
          ctx.stroke();
          ctx.setLineDash([]);

        } else if (studentFilled && isCorrectOpt === null) {
          // ── No key provided: draw blue circle for all student selections
          ctx.beginPath();
          ctx.arc(c.x, c.y, c.r, 0, Math.PI * 2);
          ctx.fillStyle = isSusp ? "rgba(211,47,47,0.35)" : "rgba(25,118,210,0.30)";
          ctx.fill();

          ctx.beginPath();
          ctx.arc(c.x, c.y, c.r, 0, Math.PI * 2);
          ctx.strokeStyle = isSusp ? "#d32f2f" : "#1976d2";
          ctx.lineWidth = 2;
          ctx.setLineDash([]);
          ctx.stroke();
        }
      }

      // ── Suspicious badge: orange dot + "?" at question center (Android paintSuspicious)
      if (isSusp) {
        // Pick center of first option cell as anchor for badge
        const anchor = questionCenter(q, OPTIONS[0]);
        if (anchor) {
          const block = BLOCKS.find((b) => q >= b.start && q < b.start + 10);
          const bx = block ? block.x1 - 14 : anchor.x - anchor.r * 3;
          const by = anchor.y;
          ctx.beginPath();
          ctx.arc(bx, by, 7, 0, Math.PI * 2);
          ctx.fillStyle = "#FF9800";
          ctx.fill();
          ctx.fillStyle = "#fff";
          ctx.font = "bold 9px sans-serif";
          ctx.textAlign = "center";
          ctx.textBaseline = "middle";
          ctx.fillText("?", bx, by);
        }
      }
    }

    return canvas.toDataURL("image/jpeg", 0.88);
  }

  function answerToSet(answerItem) {
    if (!answerItem) return new Set();
    const selected = Array.isArray(answerItem.selected) ? answerItem.selected : [];
    return new Set(selected.map((v) => String(v).trim().toUpperCase()).filter((v) => OPTIONS.includes(v)));
  }

  function compareAnswers(studentResult, keyResult, options = {}) {
    const resolveSuspicious = options.resolveSuspicious !== false;
    if (!studentResult?.answers || !keyResult?.answers) {
      return { correctCount: 0, total: 0, score10: 0, resolvedSuspiciousCount: 0, resolvedAnswers: [] };
    }

    let correct = 0;

    // Count how many questions the answer key actually has (mask > 0)
    const totalQuestions = keyResult.answers.filter((a) => a.mask > 0).length || 60;

    // Mirror Android ScoringEngine:
    // - Compare student vs key per-question, count correct.
    // - If a question is suspicious but the student answer exactly matches the key
    //   → clear the suspicious flag (Android: isSuspicious = false when isCorrect).
    const resolvedAnswers = (studentResult.answers || []).map((ans) => {
      const k = answerToSet(keyResult.answers.find((x) => x.q === ans.q));
      const s = answerToSet(ans);
      const sameSize = s.size === k.size;
      const allIn = [...s].every((v) => k.has(v));
      const isCorrect = sameSize && allIn && s.size > 0;
      const missedRequired = k.size > 0 && s.size === 0;
      const derivedSuspicious = !!ans.suspicious || missedRequired;
      if (isCorrect) correct += 1;
      // Keep unanswered required questions in suspicious review bucket.
      if (!resolveSuspicious) return { ...ans, suspicious: derivedSuspicious };
      return { ...ans, suspicious: derivedSuspicious && !isCorrect };
    });

    const resolvedSuspiciousCount = resolvedAnswers.filter((a) => a.suspicious).length;

    return {
      correctCount: correct,
      total: totalQuestions,
      score10: Number(((correct / totalQuestions) * 10).toFixed(2)),
      resolvedSuspiciousCount,
      resolvedAnswers
    };
  }

  function scoreWithoutKey(result) {
    const answers = result?.answers || [];
    return {
      correctCount: 0,
      total: 60,
      score10: 0,
      resolvedSuspiciousCount: answers.filter((a) => a?.suspicious).length,
      resolvedAnswers: answers
    };
  }

  function formatOpts(answerItem) {
    const vals = Array.from(answerToSet(answerItem));
    return vals.length ? vals.join("") : "-";
  }

  function buildSuspiciousLines(studentResult, keyResult) {
    const rows = [];
    for (const item of studentResult.answers || []) {
      if (!item?.suspicious) continue;
      const keyAns = keyResult?.answers?.find((x) => x.q === item.q);
      rows.push(`Câu ${item.q}: SV tô [${formatOpts(item)}] | ĐA [${formatOpts(keyAns)}]`);
    }
    return rows;
  }

  async function renderProjects() {
    const projects = await store.listProjects();

    if (projects.length === 0) {
      ui.projectList.innerHTML = '<div class="project-item"><span>Chưa có project nào.</span></div>';
      return;
    }

    ui.projectList.innerHTML = projects
      .map((p) => {
        const dt = new Date(p.ts).toLocaleString();
        return `
          <div class="project-item">
            <div>
              <div><strong>${p.name}</strong></div>
              <div class="muted">${dt}</div>
            </div>
            <button class="btn primary" data-open-project="${p.id}" type="button">Mở</button>
          </div>
        `;
      })
      .join("");

    ui.projectList.querySelectorAll("[data-open-project]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const id = btn.getAttribute("data-open-project");
        openProject(id).catch((err) => setStatus(`Lỗi mở project: ${err.message}`));
      });
    });
  }

  async function openProject(projectId) {
    const projects = await store.listProjects();
    const project = projects.find((p) => p.id === projectId);
    if (!project) throw new Error("Không tìm thấy project");

    state.activeProject = project;
    resetKeyCache();
    state.keyOverrides = normalizeKeyOverrides(project.keyOverrides || {});
    state.editingSuspiciousId = null;
    state.editingCommittedId = null;
    state.editingKeyCode = null;
    setResultTab("student");
    ui.activeProjectTitle.textContent = project.name;
    showView("scan");

    await renderProjectImages();
    await renderResultPage();
    setStatus("Sẵn sàng thêm ảnh");
  }

  async function renderThumbs(container, rows, kind) {
    if (rows.length === 0) {
      container.innerHTML = '<div class="muted">Chưa có ảnh</div>';
      return;
    }

    const mapped = await Promise.all(rows.map(async (row) => ({ ...row, src: await dataUrlFromBlob(row.blob) })));

    container.innerHTML = mapped
      .map(
        (row) => `
          <div class="thumb">
            <img src="${row.src}" alt="${row.name}" />
            <button data-del-image="${row.id}" type="button">Xoá</button>
          </div>
        `
      )
      .join("");

    container.querySelectorAll("[data-del-image]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = Number(btn.getAttribute("data-del-image"));
        await store.deleteProjectImage(id);
        if (kind === "key") resetKeyCache();
        await renderProjectImages();
      });
    });
  }

  async function renderProjectImages() {
    if (!state.activeProject) return;

    const [students, keys] = await Promise.all([
      store.listProjectImages(state.activeProject.id, "student", false),
      store.listProjectImages(state.activeProject.id, "key", false)
    ]);

    ui.studentCountText.textContent = `${students.length} bài làm`;
    ui.keyCountText.textContent = `${keys.length} đáp án`;

    ui.clearStudentBtn.style.display = students.length > 0 ? "inline-flex" : "none";
    ui.clearKeyBtn.style.display = keys.length > 0 ? "inline-flex" : "none";

    ui.startGradingBtn.disabled =
      !state.workerReady ||
      students.length === 0 ||
      keys.length === 0 ||
      state.gradingBusy;

    await Promise.all([
      renderThumbs(ui.studentThumbList, students, "student"),
      renderThumbs(ui.keyThumbList, keys, "key")
    ]);
  }

  function openAddMode(kind) {
    state.pendingAddKind = kind;
    ui.addModeTitle.textContent = kind === "student" ? "Thêm ảnh bài làm học viên" : "Thêm ảnh đáp án";
    ui.addModeModal.classList.add("show");
  }

  function closeAddMode() {
    ui.addModeModal.classList.remove("show");
  }

  async function startCapture() {
    closeAddMode();

    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      const msg = "Camera trên điện thoại cần HTTPS. Hãy dùng tunnel HTTPS hoặc chọn Tải lên.";
      setStatus(msg);
      alert(msg);
      return;
    }

    try {
      state.captureStream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: "environment" } },
        audio: false
      });
      ui.captureVideo.srcObject = state.captureStream;
      await ui.captureVideo.play();
      ui.captureModal.classList.add("show");
    } catch (err) {
      setStatus(`Không mở được camera: ${err.message}`);
      alert(`Không mở được camera: ${err.message}`);
    }
  }

  function stopCapture() {
    if (state.captureStream) {
      for (const t of state.captureStream.getTracks()) t.stop();
      state.captureStream = null;
    }
    ui.captureVideo.srcObject = null;
    ui.captureModal.classList.remove("show");
  }

  async function onTakePhoto() {
    const w = ui.captureVideo.videoWidth || SHEET_W;
    const h = ui.captureVideo.videoHeight || SHEET_H;

    const tmp = document.createElement("canvas");
    tmp.width = w;
    tmp.height = h;
    tmp.getContext("2d").drawImage(ui.captureVideo, 0, 0, w, h);

    const blob = await new Promise((resolve) => tmp.toBlob((b) => resolve(b), "image/jpeg", 0.95));
    const bitmap = await createBitmapFromBlob(blob);

    stopCapture();
    openReview(bitmap, blob, `capture_${Date.now()}.jpg`, state.pendingAddKind, "capture");
  }

  function openReview(bitmap, blob, name, kind, source) {
    state.review.bitmap = bitmap;
    state.review.blob = blob;
    state.review.name = name;
    state.review.kind = kind;
    state.review.rotation = 0;
    state.review.source = source;

    drawReview();
    ui.reviewModal.classList.add("show");
  }

  function closeReview() {
    ui.reviewModal.classList.remove("show");
  }

  function drawReview() {
    const bmp = state.review.bitmap;
    if (!bmp) return;

    const deg = ((state.review.rotation % 360) + 360) % 360;
    const cw = ui.reviewCanvas.width;
    const ch = ui.reviewCanvas.height;

    reviewCtx.save();
    reviewCtx.clearRect(0, 0, cw, ch);
    reviewCtx.fillStyle = "#0f172a";
    reviewCtx.fillRect(0, 0, cw, ch);
    reviewCtx.translate(cw / 2, ch / 2);
    reviewCtx.rotate((deg * Math.PI) / 180);

    const fitW = deg % 180 === 0 ? bmp.width : bmp.height;
    const fitH = deg % 180 === 0 ? bmp.height : bmp.width;
    const scale = Math.min((cw * 0.95) / fitW, (ch * 0.95) / fitH);

    reviewCtx.drawImage(bmp, -bmp.width * scale * 0.5, -bmp.height * scale * 0.5, bmp.width * scale, bmp.height * scale);
    reviewCtx.restore();
  }

  async function exportReviewBlob() {
    const canvas = document.createElement("canvas");
    canvas.width = SHEET_W;
    canvas.height = SHEET_H;

    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.translate(canvas.width / 2, canvas.height / 2);
    ctx.rotate((state.review.rotation * Math.PI) / 180);

    const bmp = state.review.bitmap;
    const deg = ((state.review.rotation % 360) + 360) % 360;
    const fitW = deg % 180 === 0 ? bmp.width : bmp.height;
    const fitH = deg % 180 === 0 ? bmp.height : bmp.width;
    // Use contain-scale (Math.min) to keep the full sheet in-frame.
    // Cropping the image can remove corner markers and break normalization.
    const scale = Math.min(canvas.width / fitW, canvas.height / fitH);

    ctx.drawImage(bmp, -bmp.width * scale * 0.5, -bmp.height * scale * 0.5, bmp.width * scale, bmp.height * scale);
    return await new Promise((resolve) => canvas.toBlob((b) => resolve(b), "image/jpeg", 0.95));
  }

  async function addImagesByUpload(files) {
    closeAddMode();

    if (files.length === 1) {
      const file = files[0];
      const bmp = await createBitmapFromBlob(file);
      openReview(bmp, file, file.name, state.pendingAddKind, "upload");
      return;
    }

    for (const file of files) {
      await store.addProjectImage(state.activeProject.id, state.pendingAddKind, file, file.name);
    }
    if (state.pendingAddKind === "key") resetKeyCache();

    await renderProjectImages();
    setStatus("Đã thêm ảnh vào danh sách");
  }

  async function runSheetProcess(blob) {
    const bmp = await createBitmapFromBlob(blob);

    // Send the image at its NATURAL resolution so the WASM NormalizeSheet can
    // detect the 4 corner markers and do a correct perspective warp to 1700×2400.
    // Pre-scaling to SHEET_W×SHEET_H here would distort the aspect ratio
    // (squash x and y by different factors), shifting bubble coordinates and
    // breaking both marker detection and the grid scan.
    const w = bmp.width;
    const h = bmp.height;

    const temp = document.createElement("canvas");
    temp.width = w;
    temp.height = h;
    const ctx = temp.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(bmp, 0, 0, w, h);

    const frame = ctx.getImageData(0, 0, w, h);

    // ── PERF: E2E start ───────────────────────────────────────────────────
    const t0 = performance.now();
    const heapBefore = performance.memory?.usedJSHeapSize ?? null;
    // ─────────────────────────────────────────────────────────────────────

    const payload = await new Promise((resolve, reject) => {
      state.pendingSheetResolver = { resolve, reject };
      worker.postMessage(
        {
          type: OMR_MSG.PROCESS_SHEET,
          payload: {
            width: frame.width,
            height: frame.height,
            buffer: frame.data.buffer
          }
        },
        [frame.data.buffer]
      );
    });

    // ── PERF: E2E end ─────────────────────────────────────────────────────
    const e2e_ms = performance.now() - t0;
    const heapAfter = performance.memory?.usedJSHeapSize ?? null;
    const wp = payload.perf ?? {};
    const entry = {
      branch: "v2-yolo",
      timestamp: new Date().toISOString(),
      input_w: w,
      input_h: h,
      e2e_ms:          +e2e_ms.toFixed(2),
      yolo_ms:         +(wp.yolo_ms ?? 0).toFixed(2),
      yolo_detected:   wp.yolo_detected ?? false,
      cpp_ms:          +(wp.cpp_ms ?? 0).toFixed(2),
      worker_total_ms: +(wp.worker_total_ms ?? 0).toFixed(2),
      overhead_ms:     +(e2e_ms - (wp.worker_total_ms ?? 0)).toFixed(2),
      wasm_heap_before: wp.wasm_heap_before ?? null,
      wasm_heap_after:  wp.wasm_heap_after ?? null,
      js_heap_before:  heapBefore,
      js_heap_after:   heapAfter,
      omr_status:      payload.status ?? null,
    };
    const sheetIndex = window.__perfLog.length;
    console.log(`[PERF] sheet=${sheetIndex} e2e=${entry.e2e_ms}ms yolo=${entry.yolo_ms}ms cpp=${entry.cpp_ms}ms`);
    console.log("[PERF]", JSON.stringify(entry));
    window.__perfLog.push(entry);
    // ─────────────────────────────────────────────────────────────────────

    return {
      ...payload,
      inputWidth: w,
      inputHeight: h,
      isLandscape: w > h
    };
  }

  async function gradeAll() {
    if (!state.activeProject) return;
    if (!state.workerReady) {
      setStatus("Worker chưa sẵn sàng, chờ thêm vài giây rồi thử lại.");
      return;
    }

    const students = await store.listProjectImages(state.activeProject.id, "student", true);
    const keys = await store.listProjectImages(state.activeProject.id, "key", false);

    if (students.length === 0 || keys.length === 0) {
      setStatus("Cần có cả bài làm và đáp án trước khi chấm");
      return;
    }

    state.gradingBusy = true;
    state.gradingTotal = students.length;
    state.gradingDone = 0;
    updateProgressUI();
    ui.startGradingBtn.disabled = true;

    resetKeyCache();
    const keyNoCode = [];
    const duplicatedCode = [];
    const landscapeKeys = [];

    for (const keyRef of keys) {
      setStatus(`Đang đọc đáp án: ${keyRef.name}`);
      const keyPayload = await runSheetProcess(keyRef.blob);
      if (keyPayload.isLandscape) landscapeKeys.push(keyRef.name);

      const keyResult = {
        ...keyPayload.result,
        examCode: normalizeExamCode(keyPayload.result?.examCode)
      };
      const assets = await buildKeyPreviewAssets(keyPayload, keyRef.blob);
      const code = keyResult?.examCode;

      // Keep first key as fallback preview even if exam code is unreadable.
      if (!state.keyResult) {
        state.keyResult = keyResult;
        state.keyPreviewDataUrl = assets.previewUrl;
      }

      if (!code) {
        keyNoCode.push(keyRef.name);
        continue;
      }
      if (state.keyResultsByCode[code]) {
        duplicatedCode.push(`${code} (${keyRef.name})`);
        continue;
      }
      setKeyCacheEntry(
        code,
        keyResult,
        assets.previewUrl,
        keyRef.name,
        keyRef.blob,
        assets.processedBlob,
        assets.warpedBlob
      );
    }
    state.keyCacheBuilt = true;

    if (Object.keys(state.keyResultsByCode).length === 0 && !state.keyResult) {
      state.gradingBusy = false;
      await renderProjectImages();
      setStatus("Không đọc được mã đề từ ảnh đáp án");
      alert("Không đọc được mã đề từ tất cả ảnh đáp án. Vui lòng kiểm tra lại ảnh key.");
      return;
    }

    let missingMssv = 0;
    let missingKey = 0;
    const landscapeStudents = [];

    for (const item of students) {
      setStatus(`Đang chấm: ${item.name}`);
      await store.saveImage(item.blob);

      const payload = await runSheetProcess(item.blob);
      if (payload.isLandscape) landscapeStudents.push(item.name);

      // Keep original blob for review modal display.
      // Also store the WASM-processed binary image (1700×2400) so the
      // suspicious/committed card preview can overlay circles that align.
      const previewBlob = item.blob;
      // Use the 1700×2400 normalized binary from WASM if available; fall back to original.
      const processedBlob = payload.preview
        ? await imageDataBlob(payload.preview, payload.previewWidth, payload.previewHeight)
        : await imageDataBlob(payload.buffer, payload.width, payload.height);
      // Color warped image for annotated preview background (Android-style)
      const warpedBlob = payload.warpedPreview
        ? await imageDataBlob(payload.warpedPreview, payload.previewWidth, payload.previewHeight)
        : null;
      const result = {
        ...payload.result,
        examCode: normalizeExamCode(payload.result?.examCode)
      };
      const keyForThisStudent = getMatchedKeyResult(result.examCode);
      const hasMatchedKey = !!(result.examCode && state.keyResultsByCode[result.examCode]);
      const scoreInfo = hasMatchedKey ? compareAnswers(result, keyForThisStudent) : scoreWithoutKey(result);
      setResultMetrics(result);

      if (!result.mssv) missingMssv += 1;
      if (!hasMatchedKey) missingKey += 1;

      const missingFlags = {
        missingMssv: !result.mssv,
        missingKeyCode: !result.examCode,
        missingKeyMatch: !!(result.examCode && !hasMatchedKey)
      };
      const needsReview =
        scoreInfo.resolvedSuspiciousCount > 0 ||
        missingFlags.missingMssv ||
        missingFlags.missingKeyCode ||
        missingFlags.missingKeyMatch;
      const storedSuspiciousCount = needsReview
        ? Math.max(
            scoreInfo.resolvedSuspiciousCount,
            missingFlags.missingMssv || missingFlags.missingKeyCode || missingFlags.missingKeyMatch ? 1 : 0
          )
        : 0;

      // Use resolved answers/suspicious (suspicious cleared for correctly-answered questions,
      // mirroring Android ScoringEngine behaviour).
      const resolvedResult = {
        ...result,
        answers: scoreInfo.resolvedAnswers,
        suspiciousCount: storedSuspiciousCount
      };

      const common = {
        sourceName: item.name,
        sourceType: "student",
        mssv: result.mssv,
        examCode: result.examCode,
        answeredCount: result.answeredCount || 0,
        suspiciousCount: storedSuspiciousCount,
        correctCount: hasMatchedKey ? scoreInfo.correctCount : 0,
        totalCount: scoreInfo.total,
        score10: hasMatchedKey ? scoreInfo.score10 : 0,
        mssvValid: !!result.mssvValid,
        missingMssv: missingFlags.missingMssv,
        missingKeyCode: missingFlags.missingKeyCode,
        missingKeyMatch: missingFlags.missingKeyMatch,
        payload: resolvedResult,
        sourceBlob: item.blob,
        previewBlob,
        processedBlob,
        warpedBlob
      };

      if (needsReview) {
        await store.saveSuspicious(state.activeProject.id, common);
      } else {
        await store.saveCommittedScan(state.activeProject.id, common);
      }

      await store.markProjectImageProcessed(item.id, true);
      state.gradingDone += 1;
      updateProgressUI();
    }

    state.gradingBusy = false;
    await renderProjectImages();
    await renderResultPage();

    showView("result");

    if (missingMssv > 0 || missingKey > 0) {
      const msg = `Đã chấm xong nhưng phát hiện thiếu dữ liệu:\n- Không tìm thấy MSSV: ${missingMssv}/${students.length}\n- Không match được KeyCode: ${missingKey}/${students.length}\n\nVui lòng kiểm tra ở mục Kết quả.`;
      setStatus(msg.replace(/\n/g, " "));
      alert(msg);
    } else {
      setStatus("Đã chấm xong và chuyển sang Kết quả");
    }

    if (keyNoCode.length > 0 || duplicatedCode.length > 0 || landscapeKeys.length > 0 || landscapeStudents.length > 0) {
      const warnLines = [];
      if (keyNoCode.length > 0) warnLines.push(`- Key không đọc được mã đề (${keyNoCode.length}): ${keyNoCode.slice(0, 5).join(", ")}`);
      if (duplicatedCode.length > 0) warnLines.push(`- Key trùng mã đề (${duplicatedCode.length}): ${duplicatedCode.slice(0, 5).join(", ")}`);
      if (landscapeKeys.length > 0) warnLines.push(`- Ảnh đáp án chụp ngang (${landscapeKeys.length})`);
      if (landscapeStudents.length > 0) warnLines.push(`- Ảnh bài làm chụp ngang (${landscapeStudents.length})`);
      alert(`Cảnh báo chất lượng dữ liệu:\n${warnLines.join("\n")}`);
    }
  }

  function buildEditMapFromResult(result) {
    const map = {};
    for (const item of result.answers || []) {
      map[item.q] = [...(item.selected || [])];
    }
    return map;
  }

  function applyEditedAnswers(result, editedMap, options = {}) {
    const clearAllSuspicious = options.clearAllSuspicious !== false;
    const reviewedQuestions = options.reviewedQuestions || null;
    const updated = structuredClone(result);

    for (let q = 1; q <= 60; q += 1) {
      const selected = Array.isArray(editedMap[q]) ? editedMap[q] : [];
      const item = updated.answers.find((a) => a.q === q);
      if (!item) continue;

      const normalized = selected
        .map((v) => String(v).trim().toUpperCase())
        .filter((v) => OPTIONS.includes(v));

      item.selected = normalized;
      item.mask = normalized.reduce((acc, cur) => {
        const idx = OPTIONS.indexOf(cur);
        return idx >= 0 ? acc | (1 << idx) : acc;
      }, 0);
      if (clearAllSuspicious) {
        item.suspicious = false;
      } else if (reviewedQuestions?.has(q)) {
        item.suspicious = false;
      }
    }

    updated.suspiciousCount = clearAllSuspicious ? 0 : updated.answers.filter((a) => a.suspicious).length;
    updated.answeredCount = updated.answers.filter((a) => a.mask !== 0).length;
    return updated;
  }

  function computeMissingFlags(resultPayload) {
    const code = normalizeExamCode(resultPayload?.examCode);
    return {
      missingMssv: !resultPayload?.mssv,
      missingKeyCode: !code,
      missingKeyMatch: !!(code && !state.keyResultsByCode[code])
    };
  }

  async function resolveSuspiciousAsCommitted(suspiciousId, row, payloadOverride = null) {
    const payload = payloadOverride || row.payload;
    const normalizedPayload = {
      ...payload,
      examCode: normalizeExamCode(payload.examCode || row.examCode)
    };
    const keyResult = getMatchedKeyResult(normalizedPayload.examCode);
    const scoreInfo = keyResult ? compareAnswers(normalizedPayload, keyResult) : scoreWithoutKey(normalizedPayload);
    await store.resolveSuspiciousToCommitted(suspiciousId, {
      sourceName: row.sourceName,
      sourceType: row.sourceType,
      mssv: normalizedPayload.mssv || row.mssv,
      examCode: normalizedPayload.examCode || row.examCode,
      answeredCount: normalizedPayload.answeredCount || row.answeredCount || 0,
      suspiciousCount: 0,
      correctCount: scoreInfo.correctCount,
      totalCount: scoreInfo.total,
      score10: scoreInfo.score10,
      mssvValid: !!normalizedPayload.mssvValid,
      missingMssv: false,
      missingKeyCode: false,
      missingKeyMatch: false,
      payload: { ...normalizedPayload, suspiciousCount: 0 },
      previewBlob: row.previewBlob,
      sourceBlob: row.sourceBlob || null,
      processedBlob: row.processedBlob || null,
      warpedBlob: row.warpedBlob || null
    });
  }

  async function saveSuspiciousEdit(id, updates) {
    await store.updateSuspicious(id, updates);
  }

  async function saveCommittedEdit(id, updates) {
    await store.updateCommittedScan(id, updates);
  }

  async function persistSuspiciousPayload(row, payload, options = {}) {
    const normalizedPayload = {
      ...payload,
      examCode: normalizeExamCode(payload.examCode || row.examCode)
    };
    const keyResult = getMatchedKeyResult(normalizedPayload.examCode);
    const scoreInfo = keyResult
      ? compareAnswers(normalizedPayload, keyResult, { resolveSuspicious: options.resolveSuspicious !== false })
      : scoreWithoutKey(normalizedPayload);
    const nextPayload = {
      ...normalizedPayload,
      answers: scoreInfo.resolvedAnswers,
      suspiciousCount: scoreInfo.resolvedSuspiciousCount
    };
    const flags = computeMissingFlags(nextPayload);
    const blockedByMissingCode = flags.missingKeyCode || flags.missingKeyMatch;
    const suspiciousCount = blockedByMissingCode
      ? Math.max(1, scoreInfo.resolvedSuspiciousCount)
      : scoreInfo.resolvedSuspiciousCount;

    await saveSuspiciousEdit(row.id, {
      mssv: nextPayload.mssv,
      examCode: nextPayload.examCode,
      answeredCount: nextPayload.answeredCount || 0,
      suspiciousCount,
      correctCount: scoreInfo.correctCount,
      totalCount: scoreInfo.total,
      score10: blockedByMissingCode ? 0 : scoreInfo.score10,
      mssvValid: !!nextPayload.mssvValid,
      missingMssv: flags.missingMssv,
      missingKeyCode: flags.missingKeyCode,
      missingKeyMatch: flags.missingKeyMatch,
      payload: { ...nextPayload, suspiciousCount }
    });
  }

  function setReviewActionVisibility(mode) {
    const isSuspicious = mode === "suspicious";
    const isStudent = mode !== "key";
    ui.editIdentityBtn.style.display = isStudent ? "inline-flex" : "none";
    ui.reviewConfirmBtn.style.display = isSuspicious ? "inline-flex" : "none";
  }

  async function openReviewSheetModal(row, mode) {
    const isSuspicious = mode === "suspicious";
    const isKey = mode === "key";
    state.reviewMode = mode;
    state.reviewDirty = false;
    state.reviewedQuestions = new Set();
    state.editingSuspiciousId = isSuspicious ? row.id : null;
    state.editingCommittedId = mode === "committed" ? row.id : null;
    state.editingKeyCode = isKey ? row.examCode : null;
    state.editingMap = buildEditMapFromResult(row.payload);
    state.editingQuestion = null;

    const keyResult = getMatchedKeyResult(row.examCode);
    const scoreInfo = !isKey && keyResult ? compareAnswers(row.payload, keyResult) : scoreWithoutKey(row.payload);
    const flags = !isKey ? computeMissingFlags(row.payload || row) : { missingMssv: false, missingKeyCode: false, missingKeyMatch: false };

    if (isKey) {
      ui.reviewTitle.textContent = `Đáp án mã đề ${row.examCode || "-"}`;
      ui.reviewMeta.textContent = `File: ${row.sourceName || "-"} | Đã nhận diện: ${row.payload?.answeredCount || 0}/60`;
    } else {
      ui.reviewTitle.textContent = isSuspicious ? "Bài làm cần xem lại" : "Chi tiết bài làm";
      ui.reviewMeta.textContent = `MSSV: ${row.mssv || "-"} | Mã đề: ${row.examCode || "-"} | Điểm: ${scoreInfo.score10.toFixed(2)} (${scoreInfo.correctCount}/60)`;
    }

    const lines = !isKey ? buildSuspiciousLines(row.payload, keyResult) : [];
    const flagLines = [];
    if (!isKey && flags.missingMssv) flagLines.push("Thiếu MSSV.");
    if (!isKey && flags.missingKeyCode) flagLines.push("Thiếu Key Code.");
    if (!isKey && flags.missingKeyMatch) flagLines.push(`Không có đáp án cho mã đề ${row.examCode || "-"}.`);

    if (isSuspicious && (lines.length > 0 || flagLines.length > 0)) {
      ui.reviewWarn.style.display = "block";
      const suspiciousHtml = lines.length > 0 ? `${lines.length} câu nghi vấn:<br/>${lines.join("<br/>")}` : "";
      const flagHtml = flagLines.length > 0 ? flagLines.join("<br/>") : "";
      ui.reviewWarn.innerHTML = [suspiciousHtml, flagHtml].filter(Boolean).join("<br/>");
    } else if (isKey && (row.payload?.answeredCount || 0) < 60) {
      ui.reviewWarn.style.display = "block";
      ui.reviewWarn.innerHTML = `Đáp án hiện chỉ nhận diện ${(row.payload?.answeredCount || 0)}/60 câu. Bạn có thể chỉnh tay.`;
    } else {
      ui.reviewWarn.style.display = "none";
      ui.reviewWarn.innerHTML = "";
    }

    if (isKey) {
      const keyPreviewUrl = getKeyPreviewForExamCode(row.examCode);
      const keyOverlayUrl = await buildAnnotatedPreview(row, row.payload?.answers || []);
      ui.reviewStudentLabel.textContent = "Đáp án đã nhận diện";
      ui.reviewKeyLabel.textContent = "Ảnh đáp án";
      ui.reviewStudentImg.src = keyOverlayUrl || keyPreviewUrl || "";
      ui.reviewKeyImg.src = keyPreviewUrl || "";
    } else {
      let activeRow = row;
      // Re-run worker to get the real warped Blob instead of falling back to original camera photo
      if (!row.warpedBlob && (row.sourceBlob || row.payload?.blob)) {
        try {
           const reprocess = await runSheetProcess(row.sourceBlob || row.payload.blob);
           if (reprocess.warpedPreview) {
             const warpedBlob = await blobFromRgba(reprocess.warpedPreview, 1700, 2400);
             activeRow = { ...row, warpedBlob };
           }
        } catch(e) { console.warn("Re-process warped blur failed", e); }
      }
      
      const studentUrl = await buildAnnotatedPreview(activeRow, keyResult?.answers);
      ui.reviewStudentLabel.textContent = "Bài làm học sinh";
      ui.reviewKeyLabel.textContent = "Đáp án chuẩn";
      ui.reviewStudentImg.src = studentUrl || "";
      ui.reviewKeyImg.src = getKeyPreviewForExamCode(row.examCode);
    }

    setReviewActionVisibility(mode);
    if (isSuspicious && (flags.missingKeyCode || flags.missingKeyMatch)) {
      ui.reviewConfirmBtn.disabled = true;
      ui.reviewConfirmBtn.title = "Thiếu/không khớp Key Code nên chưa thể lưu chính thức";
    } else {
      ui.reviewConfirmBtn.disabled = false;
      ui.reviewConfirmBtn.title = "";
    }

    ui.reviewQuestionList.innerHTML = (row.payload.answers || [])
      .map((a) => {
        const key = isKey ? a : keyResult?.answers?.find((x) => x.q === a.q);
        const sv = formatOpts(a);
        const da = formatOpts(key);
        const mark = a.suspicious ? "⚠" : "✓";
        const editLabel = isKey ? "Sửa đáp án" : "Sửa";
        const canMarkAsStandard = isSuspicious && a.suspicious;
        const markBtn = canMarkAsStandard
          ? `<button class="btn ghost" data-mark-standard="${a.q}" type="button">Đáp án chuẩn</button>`
          : "";
        return `
          <div class="q-row">
            <strong>${mark} Câu ${a.q}</strong>
            <span>SV: ${sv}</span>
            <span>ĐA: ${da}</span>
            <span class="row" style="justify-content:flex-end;">
              ${markBtn}
              <button class="btn ghost" data-inline-edit="${a.q}" type="button">${editLabel}</button>
            </span>
          </div>
        `;
      })
      .join("");

    ui.reviewQuestionList.querySelectorAll("[data-inline-edit]").forEach((x) => {
      x.addEventListener("click", async () => {
        const q = Number(x.getAttribute("data-inline-edit"));
        state.editingQuestion = q;
        ui.editMeta.textContent = isKey
          ? `Mã đề ${row.examCode || "-"} · Câu ${q}`
          : `${row.sourceName} · MSSV: ${row.mssv || "-"} · Câu ${q}`;
        renderEditQuestions(row.payload);
        ui.editModal.classList.add("show");
      });
    });

    ui.reviewQuestionList.querySelectorAll("[data-mark-standard]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        if (!state.activeProject || !state.editingSuspiciousId) return;
        const q = Number(btn.getAttribute("data-mark-standard"));
        const rows = await store.listSuspicious(state.activeProject.id);
        const current = rows.find((x) => x.id === state.editingSuspiciousId);
        if (!current) return;

        const updated = structuredClone(current.payload || {});
        updated.answers = (updated.answers || []).map((ans) => (ans.q === q ? { ...ans, suspicious: false } : ans));
        updated.suspiciousCount = (updated.answers || []).filter((ans) => ans.suspicious).length;

        state.reviewDirty = true;
        state.reviewedQuestions.add(q);
        await persistSuspiciousPayload(current, updated, { resolveSuspicious: false });

        const refreshed = (await store.listSuspicious(state.activeProject.id)).find((x) => x.id === current.id);
        if (refreshed) await openReviewSuspiciousModal(refreshed);
      });
    });

    ui.reviewModal2.classList.add("show");
  }

  async function openReviewSuspiciousModal(row) {
    await openReviewSheetModal(row, "suspicious");
  }

  async function openReviewCommittedModal(row) {
    await openReviewSheetModal(row, "committed");
  }

  async function openReviewKeyModal(row) {
    await openReviewSheetModal(row, "key");
  }

  function renderEditQuestions(result) {
    const questionSet = state.editingQuestion ? [state.editingQuestion] : Array.from({ length: 60 }, (_, i) => i + 1);

    ui.editQuestionGrid.innerHTML = questionSet
      .map((q) => {
        const selected = state.editingMap[q] || [];
        const buttons = OPTIONS.map((opt) => {
          const on = selected.includes(opt) ? "active" : "";
          return `<button class="btn ghost ${on}" data-edit-q="${q}" data-edit-opt="${opt}" type="button">${opt}</button>`;
        }).join("");

        return `
          <div style="border:1px solid var(--line);border-radius:10px;padding:8px;margin-top:8px;">
            <div style="font-weight:700;margin-bottom:6px;">Câu ${q}</div>
            <div class="row">${buttons}</div>
          </div>
        `;
      })
      .join("");

    ui.editQuestionGrid.querySelectorAll("[data-edit-q]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const q = Number(btn.getAttribute("data-edit-q"));
        const opt = btn.getAttribute("data-edit-opt");

        const set = new Set(state.editingMap[q] || []);
        if (set.has(opt)) set.delete(opt);
        else set.add(opt);
        state.editingMap[q] = Array.from(set);
        state.reviewDirty = true;
        state.reviewedQuestions.add(q);

        if (state.editingSuspiciousId && state.activeProject) {
          const rows = await store.listSuspicious(state.activeProject.id);
          const row = rows.find((x) => x.id === state.editingSuspiciousId);
          if (row) {
            const updated = applyEditedAnswers(row.payload, state.editingMap, {
              clearAllSuspicious: false,
              reviewedQuestions: state.reviewedQuestions
            });
            await persistSuspiciousPayload(row, updated, { resolveSuspicious: false });
          }
        } else if (state.editingCommittedId && state.activeProject) {
          const rows = await store.listCommittedScans(state.activeProject.id);
          const row = rows.find((x) => x.id === state.editingCommittedId);
          if (row) {
            const updated = applyEditedAnswers(row.payload, state.editingMap);
            const keyResult = getMatchedKeyResult(updated.examCode || row.examCode);
            const scoreInfo = keyResult ? compareAnswers(updated, keyResult) : scoreWithoutKey(updated);
            await saveCommittedEdit(row.id, {
              mssv: updated.mssv,
              examCode: updated.examCode,
              answeredCount: updated.answeredCount || 0,
              suspiciousCount: 0,
              correctCount: scoreInfo.correctCount,
              totalCount: scoreInfo.total,
              score10: scoreInfo.score10,
              mssvValid: !!updated.mssvValid,
              payload: updated
            });
          }
        } else if (state.editingKeyCode) {
          const code = state.editingKeyCode;
          const keyResult = state.keyResultsByCode[code];
          if (keyResult) {
            const updated = applyEditedAnswers(keyResult, state.editingMap);
            state.keyResultsByCode[code] = updated;
            if (state.defaultKeyCode === code) state.keyResult = updated;
            state.keyOverrides[code] = answersToOverrideMap(updated);
            await persistKeyOverrides();
          }
        }

        renderEditQuestions(result);
      });
    });
  }

  function listSuspiciousQuestions(payload) {
    return (payload?.answers || []).filter((a) => a?.suspicious).map((a) => a.q);
  }

  async function editCurrentStudentIdentity() {
    if (!state.activeProject) return;
    if (!state.editingSuspiciousId && !state.editingCommittedId) return;

    const isSuspicious = !!state.editingSuspiciousId;
    const rows = isSuspicious
      ? await store.listSuspicious(state.activeProject.id)
      : await store.listCommittedScans(state.activeProject.id);
    const rowId = isSuspicious ? state.editingSuspiciousId : state.editingCommittedId;
    const row = rows.find((x) => x.id === rowId);
    if (!row) return;

    const curMssv = String(row.payload?.mssv || row.mssv || "").trim();
    const curExamCode = String(row.payload?.examCode || row.examCode || "").trim();
    const nextMssvInput = prompt("Nhập MSSV (để trống nếu chưa có):", curMssv);
    if (nextMssvInput === null) return;
    const nextExamCodeInput = prompt("Nhập Key Code/Mã đề (để trống nếu chưa có):", curExamCode);
    if (nextExamCodeInput === null) return;

    const nextMssv = nextMssvInput.trim();
    const nextExamCode = normalizeExamCode(nextExamCodeInput.trim());
    const prevExamCode = normalizeExamCode(curExamCode);
    const examCodeChanged = prevExamCode !== nextExamCode;
    if (examCodeChanged) {
      const doRegradeNow = confirm(`Bạn có muốn chấm lại với mã đề mới ${nextExamCode || "-"} không?`);
      if (!doRegradeNow) return;
    }
    const updatedPayload = structuredClone(row.payload || {});
    updatedPayload.mssv = nextMssv || "";
    updatedPayload.examCode = nextExamCode || "";

    if (isSuspicious) {
      await persistSuspiciousPayload(row, updatedPayload, { resolveSuspicious: false });
      const refreshed = (await store.listSuspicious(state.activeProject.id)).find((x) => x.id === row.id);
      if (refreshed) await openReviewSuspiciousModal(refreshed);
      return;
    }

    const keyResult = getMatchedKeyResult(updatedPayload.examCode || row.examCode);
    const hasMatchedKey = !!(updatedPayload.examCode && state.keyResultsByCode[updatedPayload.examCode]);
    const scoreInfo = hasMatchedKey
      ? compareAnswers(updatedPayload, keyResult, { resolveSuspicious: false })
      : scoreWithoutKey(updatedPayload);

    await saveCommittedEdit(row.id, {
      mssv: updatedPayload.mssv || null,
      examCode: updatedPayload.examCode || null,
      answeredCount: updatedPayload.answeredCount || 0,
      suspiciousCount: 0,
      correctCount: hasMatchedKey ? scoreInfo.correctCount : 0,
      totalCount: scoreInfo.total,
      score10: hasMatchedKey ? scoreInfo.score10 : 0,
      mssvValid: !!updatedPayload.mssvValid,
      payload: updatedPayload
    });

    const refreshed = (await store.listCommittedScans(state.activeProject.id)).find((x) => x.id === row.id);
    if (refreshed) await openReviewCommittedModal(refreshed);
  }

  async function closeSuspiciousReviewWithPrompt() {
    if (!state.activeProject || !state.editingSuspiciousId) {
      ui.reviewModal2.classList.remove("show");
      state.editingQuestion = null;
      state.reviewMode = "";
      state.reviewDirty = false;
      state.reviewedQuestions = new Set();
      return;
    }

    const doRescore = confirm("Bạn có muốn chấm lại trước khi đóng không?");
    if (!doRescore) {
      ui.reviewModal2.classList.remove("show");
      state.editingQuestion = null;
      state.reviewMode = "";
      state.reviewDirty = false;
      state.reviewedQuestions = new Set();
      return;
    }

    const rows = await store.listSuspicious(state.activeProject.id);
    const row = rows.find((x) => x.id === state.editingSuspiciousId);
    if (!row) {
      ui.reviewModal2.classList.remove("show");
      state.editingQuestion = null;
      state.reviewMode = "";
      state.reviewDirty = false;
      state.reviewedQuestions = new Set();
      await renderResultPage();
      return;
    }

    const flags = computeMissingFlags(row.payload || row);
    if (flags.missingKeyCode || flags.missingKeyMatch) {
      alert("Bài chưa có Key Code hợp lệ nên chưa thể chấm/lưu chính thức. Vui lòng sửa Key Code trước.");
      await persistSuspiciousPayload(row, row.payload, { resolveSuspicious: false });
      ui.reviewModal2.classList.remove("show");
      state.editingQuestion = null;
      state.reviewMode = "";
      state.reviewDirty = false;
      state.reviewedQuestions = new Set();
      await renderResultPage();
      return;
    }

    const keyResult = getMatchedKeyResult(row.payload.examCode || row.examCode);
    const scoreInfo = keyResult
      ? compareAnswers(row.payload, keyResult, { resolveSuspicious: false })
      : scoreWithoutKey(row.payload);
    const pending = listSuspiciousQuestions({ ...row.payload, answers: scoreInfo.resolvedAnswers });

    if (pending.length === 0) {
      await resolveSuspiciousAsCommitted(row.id, row, { ...row.payload, answers: scoreInfo.resolvedAnswers, suspiciousCount: 0 });
      ui.reviewModal2.classList.remove("show");
      state.editingQuestion = null;
      await renderResultPage();
      return;
    }

    const pendingText = pending.join(", ");
    const confirmRemaining = confirm(
      `Còn ${pending.length} câu đáng ngờ (${pendingText}). Bạn có xác nhận các câu này là đúng để lưu chính thức không?`
    );

    if (confirmRemaining) {
      const accepted = structuredClone(row.payload);
      accepted.answers = (accepted.answers || []).map((a) => ({ ...a, suspicious: false }));
      accepted.suspiciousCount = 0;
      await resolveSuspiciousAsCommitted(row.id, row, accepted);
    } else {
      await persistSuspiciousPayload(row, row.payload, { resolveSuspicious: false });
    }

    ui.reviewModal2.classList.remove("show");
    state.editingQuestion = null;
    state.reviewMode = "";
    state.reviewDirty = false;
    state.reviewedQuestions = new Set();
    await renderResultPage();
  }

  async function renderResultPage() {
    if (!state.activeProject) return;

    const [summary, committed, suspicious, keys] = await Promise.all([
      store.getProjectSummary(state.activeProject.id),
      store.listCommittedScans(state.activeProject.id),
      store.listSuspicious(state.activeProject.id),
      store.listProjectImages(state.activeProject.id, "key", false)
    ]);

    if (keys.length > 0 && !state.keyCacheBuilt) {
      await ensureKeyCache(keys);
    } else if (!state.keyPreviewDataUrl && keys.length > 0) {
      state.keyPreviewDataUrl = await dataUrlFromBlob(keys[0].blob);
    }

    ui.resultSummary.textContent = `Tổng: ${summary.committedCount} bài | Cần xem lại: ${summary.suspiciousCount} | Đã tô: ${summary.totalAnswered}`;
    ui.reviewModal2.classList.remove("show");
    setResultTab(state.resultTab);

    const committedRows = committed.filter((row) => (row.suspiciousCount || 0) === 0);

    ui.studentList.innerHTML = committedRows.length
        ? committedRows
            .map((row) => {
              const dt = new Date(row.ts).toLocaleString();
              const scoreText = Number.isFinite(row.score10) ? row.score10.toFixed(2) : (((row.answeredCount || 0) / 60) * 10).toFixed(2);
              return `
                <div class="list-item suspicious-card" data-open-committed-review="${row.id}">
                  <div class="row" style="justify-content:space-between;">
                    <div style="flex:1;min-width:0;">
                      <div><strong>${row.mssv || row.sourceName}</strong></div>
                      <div style="text-align:center;font-size:30px;font-weight:800;color:var(--primary);line-height:1.1;margin:6px 0;">${scoreText}</div>
                      <div class="muted">${dt}</div>
                    </div>
                    <div class="list-actions">
                    <button class="icon-btn" title="Sửa" data-edit-committed="${row.id}" type="button">✎</button>
                    <button class="icon-btn" title="Xóa" data-del-committed="${row.id}" type="button">🗑</button>
                  </div>
                </div>
              </div>
            `;
          })
          .join("")
      : '<div class="list-item">Chưa có bài đã lưu.</div>';

    const keyRows = Object.keys(state.keyResultsByCode)
      .sort()
      .map((code) => {
        const payload = state.keyResultsByCode[code];
        const answered = payload?.answeredCount || 0;
        return {
          examCode: code,
          sourceName: state.keySourceByCode[code] || "-",
          payload,
          sourceBlob: state.keyBlobByCode[code] || null,
          processedBlob: state.keyProcessedByCode[code] || null,
          warpedBlob: state.keyWarpedByCode[code] || null,
          previewReady: !!state.keyPreviewByCode[code]
        };
      });

    ui.keyList.innerHTML = keyRows.length
      ? keyRows
          .map((row) => `
            <div class="list-item suspicious-card" data-open-key-review="${row.examCode}">
              <div class="row" style="justify-content:space-between;">
                <div>
                  <div><strong>Mã đề ${row.examCode}</strong></div>
                  <div class="muted">Đã nhận diện: ${row.payload?.answeredCount || 0}/60 câu</div>
                  <div class="muted">Preview contour: ${row.previewReady ? "đã sẵn sàng" : "chưa có"}</div>
                  <div class="muted">${row.sourceName}</div>
                </div>
                <div class="list-actions">
                  <button class="icon-btn" title="Sửa đáp án" data-edit-key="${row.examCode}" type="button">✎</button>
                </div>
              </div>
            </div>
          `)
          .join("")
      : '<div class="list-item">Chưa có đáp án đã nhận diện.</div>';

    ui.studentList.querySelectorAll("[data-del-committed]").forEach((btn) => {
      btn.addEventListener("click", async (event) => {
        event.stopPropagation();
        const id = Number(btn.getAttribute("data-del-committed"));
        await store.deleteCommittedScan(id);
        await renderResultPage();
      });
    });

    ui.studentList.querySelectorAll("[data-edit-committed]").forEach((btn) => {
      btn.addEventListener("click", async (event) => {
        event.stopPropagation();
        const id = Number(btn.getAttribute("data-edit-committed"));
        const row = committed.find((x) => x.id === id);
        if (!row) return;

        state.editingCommittedId = id;
        state.editingSuspiciousId = null;
        state.editingQuestion = null;
        state.editingMap = buildEditMapFromResult(row.payload);
        ui.editMeta.textContent = `${row.sourceName} · MSSV: ${row.mssv || "-"} · Chỉnh bài đã lưu`;
        renderEditQuestions(row.payload);
        ui.editModal.classList.add("show");
      });
    });

    ui.studentList.querySelectorAll("[data-open-committed-review]").forEach((card) => {
      card.addEventListener("click", async () => {
        const id = Number(card.getAttribute("data-open-committed-review"));
        const row = committed.find((x) => x.id === id);
        if (!row) return;
        await openReviewCommittedModal(row);
      });
    });

    ui.keyList.querySelectorAll("[data-edit-key]").forEach((btn) => {
      btn.addEventListener("click", async (event) => {
        event.stopPropagation();
        const code = btn.getAttribute("data-edit-key");
        const payload = state.keyResultsByCode[code];
        if (!payload) return;

        state.editingKeyCode = code;
        state.editingCommittedId = null;
        state.editingSuspiciousId = null;
        state.editingQuestion = null;
        state.editingMap = buildEditMapFromResult(payload);
        ui.editMeta.textContent = `Mã đề ${code} · Chỉnh đáp án`;
        renderEditQuestions(payload);
        ui.editModal.classList.add("show");
      });
    });

    ui.keyList.querySelectorAll("[data-open-key-review]").forEach((card) => {
      card.addEventListener("click", async () => {
        const code = card.getAttribute("data-open-key-review");
        const payload = state.keyResultsByCode[code];
        if (!payload) return;
        const row = {
          id: code,
          sourceName: state.keySourceByCode[code] || "-",
          mssv: null,
          examCode: code,
          payload,
          sourceBlob: state.keyBlobByCode[code] || null,
          previewBlob: state.keyBlobByCode[code] || null,
          processedBlob: state.keyProcessedByCode[code] || null,
          warpedBlob: state.keyWarpedByCode[code] || null
        };
        await openReviewKeyModal(row);
      });
    });

    if (suspicious.length === 0) {
      ui.suspiciousList.innerHTML = '<div class="list-item">Không có bài cần xem lại.</div>';
      return;
    }

    ui.suspiciousList.innerHTML = suspicious
      .map((row) => {
        const dt = new Date(row.ts).toLocaleString();
        const keyResult = getMatchedKeyResult(row.examCode);
        const lines = buildSuspiciousLines(row.payload, keyResult);
        const compact = lines.length ? lines.slice(0, 3).join("<br/>") : "Có dấu hiệu tô bất thường, cần kiểm tra.";
        const missingLines = [];
        if (row.missingMssv) missingLines.push("Thiếu MSSV");
        if (row.missingKeyCode) missingLines.push("Thiếu Key Code");
        if (row.missingKeyMatch) missingLines.push(`Không có đáp án mã đề ${row.examCode || "-"}`);
        const missingHtml = missingLines.length ? `<div class="muted">${missingLines.join(" | ")}</div>` : "";
        return `
          <div class="list-item suspicious suspicious-card" data-open-review="${row.id}">
            <div><strong>${row.mssv || row.sourceName}</strong></div>
            <div class="muted">Nghi ngờ: ${row.suspiciousCount} câu · ${dt}</div>
            ${missingHtml}
            <div class="banner-warn" style="margin-top:6px;">${compact}</div>
          </div>
        `;
      })
      .join("");

    ui.suspiciousList.querySelectorAll("[data-open-review]").forEach((card) => {
      card.addEventListener("click", async () => {
        const id = Number(card.getAttribute("data-open-review"));
        const row = suspicious.find((x) => x.id === id);
        if (!row) return;
        await openReviewSuspiciousModal(row);
      });
    });
  }

  async function onWorkerMessage(event) {
    const msg = event.data;

    if (msg.type === OMR_MSG.READY) {
      state.workerReady = true;
      setStatus(`Wasm sẵn sàng (protocol v${msg.protocolVersion})`);
      renderProjectImages().catch(() => {});
      return;
    }

    if (msg.type === OMR_MSG.SHEET_RESULT) {
      if (state.pendingSheetResolver) {
        state.pendingSheetResolver.resolve(msg.payload);
        state.pendingSheetResolver = null;
      }
      return;
    }

    if (msg.type === OMR_MSG.ERROR) {
      if (state.pendingSheetResolver) {
        state.pendingSheetResolver.reject(new Error(msg.error));
        state.pendingSheetResolver = null;
      }
      state.gradingBusy = false;
      setStatus(`Lỗi Worker: ${msg.error}`);
    }
  }

  ui.createProjectBtn.addEventListener("click", async () => {
    const name = ui.projectNameInput.value.trim();
    if (!name) return;

    const p = await store.createProject(name);
    ui.projectNameInput.value = "";
    await renderProjects();
    await openProject(p.id);
  });

  ui.refreshProjectsBtn.addEventListener("click", () => {
    renderProjects().catch((err) => console.error(err));
  });

  ui.backToProjectsBtn.addEventListener("click", () => {
    showView("projects");
  });

  ui.goResultBtn.addEventListener("click", async () => {
    await renderResultPage();
    showView("result");
  });

  ui.exportBtn.addEventListener("click", () => {
    openExportModal();
  });

  ui.confirmExportBtn.addEventListener("click", () => {
    const format = ui.exportFormatSelect.value === "csv" ? "csv" : "json";
    const scope = ui.exportScopeSelect.value === "full" ? "full" : "result";
    exportProjectData({ format, scope })
      .then(() => closeExportModal())
      .catch((err) => {
        setStatus(`Lỗi export: ${err.message}`);
      });
  });

  ui.cancelExportBtn.addEventListener("click", () => closeExportModal());
  ui.closeExportBtn.addEventListener("click", () => closeExportModal());
  ui.exportModal.addEventListener("click", (event) => {
    if (event.target === ui.exportModal) closeExportModal();
  });

  ui.backToScanBtn.addEventListener("click", () => {
    showView("scan");
  });

  ui.addStudentBtn.addEventListener("click", () => openAddMode("student"));
  ui.addKeyBtn.addEventListener("click", () => openAddMode("key"));

  ui.clearStudentBtn.addEventListener("click", async () => {
    await store.clearProjectImages(state.activeProject.id, "student");
    await renderProjectImages();
  });

  ui.clearKeyBtn.addEventListener("click", async () => {
    await store.clearProjectImages(state.activeProject.id, "key");
    resetKeyCache();
    await renderProjectImages();
  });

  ui.startGradingBtn.addEventListener("click", () => {
    gradeAll().catch((err) => {
      state.gradingBusy = false;
      setStatus(`Lỗi chấm bài: ${err.message}`);
    });
  });

  ui.addByCaptureBtn.addEventListener("click", () => {
    startCapture().catch((err) => setStatus(`Lỗi camera: ${err.message}`));
  });

  ui.addByUploadBtn.addEventListener("click", () => {
    closeAddMode();
    ui.uploadInput.click();
  });

  ui.cancelAddModeBtn.addEventListener("click", () => closeAddMode());

  ui.uploadInput.addEventListener("change", async (event) => {
    const files = Array.from(event.target.files || []);
    ui.uploadInput.value = "";
    if (files.length === 0) return;

    await addImagesByUpload(files);
  });

  ui.takePhotoBtn.addEventListener("click", () => {
    onTakePhoto().catch((err) => setStatus(`Lỗi chụp ảnh: ${err.message}`));
  });

  ui.cancelCaptureBtn.addEventListener("click", () => {
    stopCapture();
  });

  ui.rotateLeftBtn.addEventListener("click", () => {
    state.review.rotation -= 90;
    drawReview();
  });

  ui.rotateRightBtn.addEventListener("click", () => {
    state.review.rotation += 90;
    drawReview();
  });

  ui.retakeBtn.addEventListener("click", () => {
    closeReview();
    if (state.review.source === "capture") {
      startCapture().catch((err) => setStatus(`Lỗi camera: ${err.message}`));
    }
  });

  ui.useImageBtn.addEventListener("click", async () => {
    const blob = await exportReviewBlob();
    const name = state.review.name || `image_${Date.now()}.jpg`;
    const kind = state.review.kind;

    await store.addProjectImage(state.activeProject.id, kind, blob, name);
    if (kind === "key") resetKeyCache();
    closeReview();
    await renderProjectImages();
  });

  ui.closeEditBtn.addEventListener("click", async () => {
    ui.editModal.classList.remove("show");
    state.editingQuestion = null;
    if (!state.activeProject) return;

    if (state.editingSuspiciousId) {
      const row = (await store.listSuspicious(state.activeProject.id)).find((x) => x.id === state.editingSuspiciousId);
      if (row) await openReviewSuspiciousModal(row);
      return;
    }
    if (state.editingCommittedId) {
      const row = (await store.listCommittedScans(state.activeProject.id)).find((x) => x.id === state.editingCommittedId);
      if (row) await openReviewCommittedModal(row);
      return;
    }
    if (state.editingKeyCode) {
      const code = state.editingKeyCode;
      const payload = state.keyResultsByCode[code];
      if (!payload) return;
      await openReviewKeyModal({
        id: code,
        sourceName: state.keySourceByCode[code] || "-",
        mssv: null,
        examCode: code,
        payload,
        sourceBlob: state.keyBlobByCode[code] || null,
        previewBlob: state.keyBlobByCode[code] || null,
        processedBlob: state.keyProcessedByCode[code] || null,
        warpedBlob: state.keyWarpedByCode[code] || null
      });
    }
  });

  ui.reviewConfirmBtn.addEventListener("click", async () => {
    if (!state.editingSuspiciousId || !state.activeProject) return;
    const rows = await store.listSuspicious(state.activeProject.id);
    const row = rows.find((x) => x.id === state.editingSuspiciousId);
    if (!row) return;
    const flags = computeMissingFlags(row.payload || row);
    if (flags.missingKeyCode || flags.missingKeyMatch) {
      alert("Bài chưa có Key Code hợp lệ nên chưa thể lưu chính thức.");
      return;
    }

    await resolveSuspiciousAsCommitted(state.editingSuspiciousId, row);
    state.editingSuspiciousId = null;
    ui.reviewModal2.classList.remove("show");
    state.reviewMode = "";
    state.reviewDirty = false;
    state.reviewedQuestions = new Set();
    await renderResultPage();
  });

  ui.editIdentityBtn.addEventListener("click", () => {
    editCurrentStudentIdentity().catch((err) => setStatus(`Lỗi sửa MSSV/KeyCode: ${err.message}`));
  });

  ui.closeReviewBtn.addEventListener("click", async () => {
    if (state.reviewMode === "suspicious") {
      await closeSuspiciousReviewWithPrompt();
      return;
    }
    ui.reviewModal2.classList.remove("show");
    state.editingQuestion = null;
    state.reviewMode = "";
    state.reviewDirty = false;
    state.reviewedQuestions = new Set();
    await renderResultPage();
  });

  ui.resultTabStudentBtn.addEventListener("click", () => {
    setResultTab("student");
  });

  ui.resultTabKeyBtn.addEventListener("click", () => {
    setResultTab("key");
  });

  worker.onmessage = (event) => {
    onWorkerMessage(event).catch((err) => {
      state.gradingBusy = false;
      setStatus(`Lỗi xử lý worker: ${err.message}`);
    });
  };

  async function bootstrap() {
    await renderProjects();
    updateProgressUI();
    worker.postMessage({ type: OMR_MSG.INIT });
  }

  bootstrap().catch((err) => {
    setStatus(`Lỗi khởi tạo: ${err.message}`);
  });
})();
