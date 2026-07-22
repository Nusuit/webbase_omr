const state = {
  worker: null,
  ready: false,
  // Fixed engine: hybrid AI corner (YOLO corner detection + C++/WASM grading).
  engine: "corner",
  keyFiles: [],
  sheetFiles: [],
  keys: [],
  results: [],
  questionCount: 60,
  answerEditor: null,
  selected: null
};

const letters = ["A", "B", "C", "D", "E"];
const OMR_LAYOUT = {
  blocks: [
    { start: 1, end: 10, x1: 880, y1: 860, x2: 1183, y2: 1530, rows: 10, cols: 5 },
    { start: 11, end: 20, x1: 1249, y1: 856, x2: 1563, y2: 1527, rows: 10, cols: 5 },
    { start: 21, end: 30, x1: 140, y1: 1604, x2: 447, y2: 2311, rows: 10, cols: 5 },
    { start: 31, end: 40, x1: 515, y1: 1597, x2: 819, y2: 2303, rows: 10, cols: 5 },
    { start: 41, end: 50, x1: 882, y1: 1593, x2: 1194, y2: 2311, rows: 10, cols: 5 },
    { start: 51, end: 60, x1: 1251, y1: 1585, x2: 1568, y2: 2292, rows: 10, cols: 5 }
  ]
};

const els = {
  status: document.getElementById("status"),
  keyInput: document.getElementById("keyInput"),
  sheetInput: document.getElementById("sheetInput"),
  questionCountInput: document.getElementById("questionCountInput"),
  scanKeysBtn: document.getElementById("scanKeysBtn"),
  scanSheetsBtn: document.getElementById("scanSheetsBtn"),
  exportBtn: document.getElementById("exportBtn"),
  exportMenu: document.getElementById("exportMenu"),
  exportCsvBtn: document.getElementById("exportCsvBtn"),
  exportJsonBtn: document.getElementById("exportJsonBtn"),
  clearSessionBtn: document.getElementById("clearSessionBtn"),
  keyCount: document.getElementById("keyCount"),
  sheetCount: document.getElementById("sheetCount"),
  progressFill: document.getElementById("progressFill"),
  keySummary: document.getElementById("keySummary"),
  keyList: document.getElementById("keyList"),
  resultSummary: document.getElementById("resultSummary"),
  resultRows: document.getElementById("resultRows"),
  previewCanvas: document.getElementById("previewCanvas"),
  previewLabel: document.getElementById("previewLabel"),
  detailPanel: document.getElementById("detailPanel"),
  reviewList: document.getElementById("reviewList"),
  answerReviewToggle: document.getElementById("answerReviewToggle"),
  answerReviewPanel: document.getElementById("answerReviewPanel")
};

// Also removed from the DOM in the redesign: the "General Information" toggle
// (inputStatusToggle/Panel) and the keys/ready metric cards. Key details now
// live inline on each one-line key row instead.

// ── Session persistence (IndexedDB) ─────────────────────────────────────────
// Scanned keys/results survive a reload or a killed mobile tab. Records are
// plain data (answers, masks, previewImage dataUrl), so they clone directly.
const SESSION_DB = "gradesnap-session";
const SESSION_STORE = "session";

function openSessionDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(SESSION_DB, 1);
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(SESSION_STORE)) {
        req.result.createObjectStore(SESSION_STORE);
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function sessionTx(db, mode, run) {
  const tx = db.transaction(SESSION_STORE, mode);
  const result = run(tx.objectStore(SESSION_STORE));
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve(result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

let persistTimer = null;
function persistState() {
  clearTimeout(persistTimer);
  persistTimer = setTimeout(() => {
    persistStateNow().catch((error) => console.warn("Session save failed:", error));
  }, 600);
}

async function persistStateNow() {
  const db = await openSessionDb();
  try {
    await sessionTx(db, "readwrite", (store) => {
      store.put({
        savedAt: Date.now(),
        questionCount: state.questionCount,
        keys: state.keys,
        results: state.results
      }, "current");
    });
  } finally {
    db.close();
  }
}

async function restoreSession() {
  try {
    const db = await openSessionDb();
    let saved = null;
    try {
      await sessionTx(db, "readonly", (store) => {
        const req = store.get("current");
        req.onsuccess = () => { saved = req.result; };
      });
    } finally {
      db.close();
    }
    if (!saved || ((saved.keys || []).length === 0 && (saved.results || []).length === 0)) {
      return false;
    }
    state.questionCount = saved.questionCount || 60;
    els.questionCountInput.value = String(state.questionCount);
    state.keys = saved.keys || [];
    state.results = saved.results || [];
    recomputeScores();
    renderKeys();
    renderResults();
    renderReviewList();
    updateActions();
    if (state.results.length) selectRecord(state.results[0], "result");
    else if (state.keys.length) selectRecord(state.keys[0], "key");
    return true;
  } catch (error) {
    console.warn("Session restore failed:", error);
    return false;
  }
}

async function clearSession() {
  state.keys = [];
  state.results = [];
  state.selected = null;
  state.answerEditor = null;
  renderKeys();
  renderResults();
  renderReviewList();
  renderRecordPreview(null, null);
  els.previewLabel.textContent = "No scan selected";
  els.detailPanel.innerHTML = `
    <div class="detail-title">
      <span>No result</span>
      <span class="badge">Idle</span>
    </div>
    <div class="empty">Processed sheets will appear here.</div>
  `;
  updateActions();
  try {
    const db = await openSessionDb();
    try {
      await sessionTx(db, "readwrite", (store) => store.delete("current"));
    } finally {
      db.close();
    }
    setStatus("Session cleared", "ok");
  } catch (error) {
    setStatus(`Session cleared (storage: ${error.message})`, "warn");
  }
}

function setStatus(text, kind = "neutral") {
  els.status.textContent = text;
  els.status.style.color = kind === "ok" ? "var(--success)"
    : kind === "warn" ? "var(--warn)"
    : kind === "bad" ? "var(--danger)"
    : "var(--muted)";
}

function setProgress(done, total) {
  const pct = total > 0 ? Math.round((done / total) * 100) : 0;
  els.progressFill.style.width = `${pct}%`;
}

function workerUrl() {
  return "./js/worker-yolo.js?det=corner";
}

function initWorker() {
  if (state.worker) {
    state.worker.terminate();
    state.worker = null;
  }
  state.ready = false;
  if (location.protocol === "file:") {
    setStatus("Open with local server: http://localhost:8090/product.html", "bad");
    updateActions();
    return Promise.resolve(false);
  }
  setStatus(`Starting ${engineLabel()}...`);
  updateActions();

  return new Promise((resolve) => {
    const src = workerUrl();
    let worker;
    try {
      worker = new Worker(`${src}${src.includes("?") ? "&" : "?"}v=${Date.now()}`);
    } catch (error) {
      setStatus(error.message || "Engine failed to start", "bad");
      updateActions();
      resolve(false);
      return;
    }
    state.worker = worker;

    const onMessage = (event) => {
      const msg = event.data;
      if (!msg) return;
      if (msg.type === OMR_MSG.READY) {
        worker.removeEventListener("message", onMessage);
        state.ready = true;
        setStatus(`${engineLabel()} ready`, "ok");
        updateActions();
        resolve(true);
      } else if (msg.type === OMR_MSG.ERROR) {
        worker.removeEventListener("message", onMessage);
        state.ready = false;
        setStatus(msg.error || "Engine failed", "bad");
        updateActions();
        resolve(false);
      }
    };

    worker.addEventListener("error", (event) => {
      worker.removeEventListener("message", onMessage);
      state.ready = false;
      setStatus(event.message || "Engine failed to start", "bad");
      updateActions();
      resolve(false);
    }, { once: true });
    worker.addEventListener("message", onMessage);
    worker.postMessage({ type: OMR_MSG.INIT });
  });
}

function engineLabel() {
  return "AI engine";
}

function updateActions() {
  const hasKeys = state.keyFiles.length > 0;
  const hasReadyKeys = getReadyKeys().length > 0;
  const hasSheets = state.sheetFiles.length > 0;
  const hasQuestionCount = state.questionCount >= 1 && state.questionCount <= 60;
  els.scanKeysBtn.disabled = !state.ready || !hasKeys || !hasQuestionCount;
  els.scanSheetsBtn.disabled = !state.ready || !hasReadyKeys || !hasSheets;
  const noResults = state.results.length === 0;
  els.exportBtn.disabled = noResults;
  els.exportCsvBtn.disabled = noResults;
  els.exportJsonBtn.disabled = noResults;
  if (noResults) closeExportMenu();
}

function readyStatusText() {
  if (location.protocol === "file:") return "Open with local server: http://localhost:8090/product.html";
  return state.ready ? `${engineLabel()} ready` : `${engineLabel()} is still starting`;
}

function readyStatusKind() {
  if (location.protocol === "file:") return "bad";
  return state.ready ? "ok" : "warn";
}

function getReadyKeys() {
  return state.keys.filter((key) => {
    const analysis = analyzeKey(key);
    return key.examCode.trim() && analysis.questionCount > 0 && analysis.holes.length === 0;
  });
}

function inferQuestionCount(masks) {
  for (let i = Math.min(masks.length, 60) - 1; i >= 0; i--) {
    if ((masks[i] || 0) !== 0) return i + 1;
  }
  return 0;
}

function getQuestionCount(record) {
  const value = Number(state.questionCount);
  if (Number.isInteger(value) && value >= 1 && value <= 60) return value;
  return 0;
}

function analyzeKey(key) {
  const masks = key.masks || [];
  const lastDetected = inferQuestionCount(masks);
  const questionCount = getQuestionCount(key);
  const holes = [];
  const outOfRange = [];
  const recovered = [];
  let answeredWithin = 0;

  for (let i = 0; i < questionCount; i++) {
    if ((masks[i] || 0) === 0) holes.push(i + 1);
    else {
      answeredWithin += 1;
      if (key.answers[i]?.recovered) recovered.push(i + 1);
    }
  }
  for (let i = questionCount; i < 60; i++) {
    if ((masks[i] || 0) !== 0) outOfRange.push(i + 1);
  }

  return {
    questionCount,
    lastDetected,
    holes,
    outOfRange,
    recovered,
    answeredWithin,
    detectedCount: (key.detectedMasks || masks).filter((mask) => (mask || 0) !== 0).length
  };
}

function countedAnswerStats(record) {
  const questionCount = getQuestionCount(record);
  let answered = 0;
  let suspicious = 0;
  let multi = 0;

  for (let i = 0; i < questionCount; i++) {
    const answer = record.answers[i] || { selected: [], mask: 0 };
    if ((record.masks[i] || answer.mask || 0) !== 0) answered += 1;
    if (answer.suspicious) suspicious += 1;
    if ((answer.selected || []).length > 1) multi += 1;
  }

  return { questionCount, answered, suspicious, multi };
}

function fileCountText(files) {
  return `${files.length} ${files.length === 1 ? "file" : "files"}`;
}

function updateCounts() {
  els.keyCount.textContent = fileCountText(state.keyFiles);
  els.sheetCount.textContent = fileCountText(state.sheetFiles);
}

function keyStatusBadge(text) {
  const cls = text === "Ready" ? "ok" : "warn";
  return `<span class="badge ${cls}">${escapeHtml(text)}</span>`;
}

function renderKeys() {
  els.keySummary.textContent = state.keys.length ? `${getReadyKeys().length}/${state.keys.length} ready` : "No key";

  if (state.keys.length === 0) {
    els.keyList.innerHTML = `<div class="empty">Select answer key images and scan them.</div>`;
    return;
  }

  els.keyList.innerHTML = "";
  state.keys.forEach((key, idx) => {
    const analysis = analyzeKey(key);
    const statusText = keyStatusText(key, analysis);
    const manualText = key.manualDraft ?? manualDraftForKey(key);
    const item = document.createElement("div");
    item.className = "key-item";
    // One-line row: filename · editable code · questions/detected · status pill.
    // Warnings and the manual-fix editor only render for keys that need attention.
    item.innerHTML = `
      <div class="key-row">
        <span class="key-name" title="${escapeHtml(key.fileName)}">${escapeHtml(key.fileName)}</span>
        <span class="key-meta">Code <input class="key-code" data-key-code value="${escapeHtml(key.examCode)}" placeholder="set code" inputmode="numeric" aria-label="Exam code for ${escapeHtml(key.fileName)}" /> · ${analysis.questionCount || "-"}Q · <span class="k" title="detected bubbles">${analysis.detectedCount}/60</span></span>
        ${keyStatusBadge(statusText)}
      </div>
      ${keyWarningHtml(key, analysis)}
      ${manualFixHtml(key, analysis, manualText)}
    `;
    item.querySelector("[data-key-code]").addEventListener("input", (event) => {
      key.examCode = event.target.value.trim();
      recomputeScores();
      renderResults();
      updateActions();
      persistState();
    });
    item.querySelector("[data-key-code]").addEventListener("change", () => renderKeys());
    bindManualFix(item, key);
    item.querySelectorAll("input, textarea, button, summary").forEach((control) => {
      control.addEventListener("click", (event) => event.stopPropagation());
    });
    item.addEventListener("click", () => selectRecord(key, "key"));
    els.keyList.appendChild(item);
  });
}

function keyStatusText(key, analysis) {
  if (!key.examCode.trim()) return "Needs code";
  if (analysis.questionCount === 0) return "Needs answers";
  if (analysis.holes.length > 0) return "Check key";
  if (analysis.recovered.length > 0) return "Review";
  return "Ready";
}

function keyWarningHtml(key, analysis) {
  const alerts = [];
  if (key.manualError) {
    alerts.push(key.manualError);
  }
  if (!key.examCode.trim()) {
    alerts.push("Exam code was not detected. Enter it in this key's Code field to activate the key.");
  }
  if (analysis.holes.length > 0) {
    const firstHole = analysis.holes[0];
    alerts.push(
      `Blank inside first ${analysis.questionCount} questions: ${compactQuestionList(analysis.holes)}. ` +
      `Change Questions above, retake the key photo, or fill Q${firstHole} manually.`
    );
  }
  if (analysis.recovered.length > 0) {
    alerts.push(
      `Recovered key answer(s): ${compactQuestionList(analysis.recovered)}. Please verify the highlighted cells.`
    );
  }
  return alerts.map((text) => `<div class="key-alert">${escapeHtml(text)}</div>`).join("");
}

function compactQuestionList(questions, limit = 6) {
  const shown = questions.slice(0, limit).map((q) => `Q${q}`).join(", ");
  return questions.length > limit ? `${shown}, +${questions.length - limit} more` : shown;
}

function manualFixHtml(key, analysis, manualText) {
  if (!key.manualError && analysis.holes.length === 0 && analysis.recovered.length === 0) return "";
  return `
    <details class="manual-entry">
      <summary>Fix manually</summary>
      <textarea data-manual-answers spellcheck="false">${escapeHtml(manualText)}</textarea>
      <div class="manual-actions">
        <button class="mini-action primary" type="button" data-apply-manual>Apply manual answers</button>
        <button class="mini-action" type="button" data-use-detected>Use detected</button>
      </div>
      <div class="manual-hint">Use <strong>52:C</strong> to fill one question, or paste a sequence like <strong>A B C D E</strong>. Blank is <strong>-</strong>.</div>
    </details>
  `;
}

function bindManualFix(item, key) {
  const textarea = item.querySelector("[data-manual-answers]");
  if (!textarea) return;
  textarea.addEventListener("input", (event) => {
    key.manualDraft = event.target.value;
  });
  item.querySelector("[data-apply-manual]").addEventListener("click", () => {
    applyManualAnswers(key, textarea.value);
    recomputeScores();
    renderKeys();
    renderResults();
    updateActions();
    selectRecord(key, "key");
  });
  item.querySelector("[data-use-detected]").addEventListener("click", () => {
    restoreDetectedAnswers(key);
    recomputeScores();
    renderKeys();
    renderResults();
    updateActions();
    selectRecord(key, "key");
  });
}

function clampQuestionCount(value) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) return 0;
  return Math.max(1, Math.min(60, parsed));
}

function syncQuestionCountFromInput(announce = false) {
  const count = clampQuestionCount(els.questionCountInput.value);
  if (!count) {
    state.questionCount = 0;
    updateActions();
    if (announce) setStatus("Set Questions between 1 and 60 before scanning", "bad");
    return false;
  }

  state.questionCount = count;
  els.questionCountInput.value = String(count);
  recomputeScores();
  renderKeys();
  renderResults();
  updateActions();
  if (state.selected) renderDetail(state.selected.record, state.selected.type);
  if (announce) setStatus(`Questions set to ${count}`, "ok");
  persistState();
  return true;
}

function manualDraftForKey(key) {
  const count = Math.max(getQuestionCount(key), inferQuestionCount(key.masks || []), 1);
  return answersToText(key.answers, count);
}

function answersToText(answers, count) {
  const out = [];
  for (let i = 0; i < Math.min(count, 60); i++) {
    const answer = answers[i] || { selected: [] };
    out.push(`${i + 1}:${answer.selected.length ? answer.selected.join("+") : "-"}`);
  }
  return out.join(" ");
}

function applyManualAnswers(key, text) {
  try {
    const parsed = parseManualAnswers(text, key);
    parsed.updates.forEach(({ index, mask }) => setAnswerMask(key, index, mask, { manual: true }));
    key.masks = key.answers.map((answer) => answer.mask || 0);
    key.answeredCount = key.masks.filter((mask) => mask !== 0).length;
    key.manualDraft = manualDraftForKey(key);
    key.manualError = null;
    setStatus(`Updated manual answers for ${key.fileName}`, "ok");
    persistState();
  } catch (error) {
    key.manualError = error.message;
    setStatus(error.message, "bad");
  }
}

function parseManualAnswers(text, key) {
  const source = String(text || "").trim();
  if (!source) throw new Error("Manual answers are empty");

  const updates = [];
  const numbered = /(?:^|[\s,;])q?(\d{1,2})\s*[:=]\s*([A-E](?:\s*\+\s*[A-E])?|[-_])(?=$|[\s,;])/gi;
  let match;
  while ((match = numbered.exec(source)) !== null) {
    const question = Number.parseInt(match[1], 10);
    if (question < 1 || question > 60) throw new Error(`Question ${question} is out of range`);
    updates.push({ index: question - 1, mask: answerTokenToMask(match[2]) });
  }

  if (updates.length > 0) return { updates };

  const tokens = source
    .toUpperCase()
    .replace(/\s*\+\s*/g, "+")
    .split(/[\s,;]+/)
    .filter(Boolean);
  if (tokens.length === 0) throw new Error("Manual answers are empty");
  if (tokens.length > 60) throw new Error("Manual answer list has more than 60 questions");

  const replaceUpdates = [];
  for (let i = 0; i < 60; i++) {
    const token = i < tokens.length ? tokens[i] : "-";
    replaceUpdates.push({ index: i, mask: answerTokenToMask(token) });
  }
  state.questionCount = tokens.length;
  els.questionCountInput.value = String(tokens.length);
  return { updates: replaceUpdates };
}

function answerTokenToMask(token) {
  const value = String(token || "").toUpperCase().replace(/\s/g, "");
  if (value === "-" || value === "_") return 0;
  const selected = value.split("+").filter(Boolean);
  if (selected.length === 0 || selected.some((letter) => !letters.includes(letter))) {
    throw new Error(`Invalid answer token: ${token}`);
  }
  return lettersToMask(selected);
}

function setAnswerMask(record, index, mask, meta = {}) {
  const selected = maskToLetters(mask);
  record.answers[index] = {
    q: index + 1,
    mask,
    selected,
    suspicious: !!meta.suspicious,
    recovered: !!meta.recovered,
    manual: !!meta.manual,
    confidence: meta.confidence ?? null,
    fillRatio: meta.fillRatio ?? null
  };
}

function restoreDetectedAnswers(key) {
  if (!key.detectedAnswers) return;
  key.answers = cloneAnswers(key.detectedAnswers);
  key.masks = [...key.detectedMasks];
  key.answeredCount = key.masks.filter((mask) => mask !== 0).length;
  key.manualDraft = manualDraftForKey(key);
  key.manualError = null;
  setStatus(`Restored detected answers for ${key.fileName}`, "ok");
  persistState();
}

function renderResults() {
  const total = state.results.length;
  const scored = state.results.filter((r) => r.score !== null).length;
  const review = state.results.filter((r) => r.status !== "OK").length;
  els.resultSummary.textContent = total ? `${scored}/${total} scored, ${review} review` : "0 processed";

  if (total === 0) {
    els.resultRows.innerHTML = `<tr><td colspan="7" class="empty">No student sheets processed.</td></tr>`;
    renderReviewList();
    return;
  }

  els.resultRows.innerHTML = "";
  state.results.forEach((row) => {
    const stats = countedAnswerStats(row);
    const tr = document.createElement("tr");
    tr.className = "result-row";
    tr.innerHTML = `
      <td class="file" title="${escapeHtml(row.fileName)}">${escapeHtml(row.fileName)}</td>
      <td class="col-mssv">${escapeHtml(row.mssv || "-")}</td>
      <td>${escapeHtml(row.examCode || "-")}</td>
      <td class="col-answered">${stats.answered}/${stats.questionCount || "-"}</td>
      <td class="col-review" title="${stats.suspicious} low-confidence answer(s)">${row.reviewCount}${stats.suspicious ? ` <span class="sus-note">+${stats.suspicious}?</span>` : ""}</td>
      <td class="score">${row.score === null ? "-" : row.score.toFixed(2)}</td>
      <td>${statusBadge(row.status)}</td>
    `;
    tr.addEventListener("click", () => selectRecord(row, "result", { scroll: true }));
    els.resultRows.appendChild(tr);
  });
  renderReviewList();
}

function statusBadge(status) {
  if (status === "OK") return `<span class="badge ok">OK</span>`;
  if (status === "No key") return `<span class="badge bad">No key</span>`;
  if (status === "Check image") return `<span class="badge bad">Check image</span>`;
  return `<span class="badge warn">Review</span>`;
}

function selectRecord(record, type, opts = {}) {
  if (!state.selected || state.selected.record !== record || state.selected.type !== type) {
    state.answerEditor = null;
  }
  state.selected = { record, type };
  els.previewLabel.textContent = record.fileName || "Selected scan";
  renderRecordPreview(record, type);
  renderDetail(record, type);
  renderReviewList();
  // On narrow screens the detail panel sits far above the results table, so an
  // explicit user tap must bring it into view or the tap looks like a no-op.
  if (opts.scroll && window.matchMedia("(max-width: 720px)").matches) {
    if (els.answerReviewPanel.classList.contains("collapsed")) {
      els.answerReviewPanel.classList.remove("collapsed");
      els.answerReviewToggle.classList.remove("collapsed");
    }
    els.detailPanel.scrollIntoView({ behavior: "smooth", block: "start" });
  }
}

function renderDetail(record, type) {
  const keyAnalysis = type === "key" ? analyzeKey(record) : null;
  const keyStatus = keyAnalysis ? keyStatusText(record, keyAnalysis) : "";
  const badge = type === "key"
    ? (keyStatus === "Ready" ? `<span class="badge ok">Key</span>` : `<span class="badge warn">${escapeHtml(keyStatus)}</span>`)
    : statusBadge(record.status);
  const resultStats = type === "result" ? countedAnswerStats(record) : null;
  const subtitle = type === "key"
    ? `Code ${record.examCode || "-"} | questions ${keyAnalysis.questionCount || "-"} | used ${keyAnalysis.answeredWithin}/${keyAnalysis.questionCount || 0}`
    : `MSSV ${record.mssv || "-"} | code ${record.examCode || "-"} | answered ${resultStats.answered}/${resultStats.questionCount || "-"}`;

  els.detailPanel.innerHTML = `
    <div class="detail-title">
      <span title="${escapeHtml(record.fileName)}">${escapeHtml(record.fileName)}</span>
      ${badge}
    </div>
    <div style="color:var(--muted); font-size:13px; margin-bottom:10px;">${escapeHtml(subtitle)}</div>
    ${type === "key" ? keyWarningHtml(record, keyAnalysis) : resultFieldsHtml(record) + qualityAlertHtml(record)}
    ${type === "result" ? `<div class="answer-legend"><span><i class="g"></i>correct</span><span><i class="y"></i>suspend</span></div>` : ""}
    <div class="answer-grid">
      ${record.answers.map((answer, idx) => {
        const label = answer.selected.length ? answer.selected.join("+") : "";
        const cls = answerCellClass(record, type, idx, answer);
        const title = answer.recovered
          ? `Q${idx + 1} recovered (${Math.round((answer.fillRatio || 0) * 1000) / 1000})`
          : `Q${idx + 1}`;
        return `<button class="answer-cell ${cls}" type="button" data-answer-idx="${idx}" title="${escapeHtml(title)}">${idx + 1}:${escapeHtml(label || "-")}</button>`;
      }).join("")}
    </div>
    ${answerEditorHtml(record, type)}
  `;
  if (type === "result") bindResultFields(record);
  bindAnswerGrid(record, type);
}

// Editable MSSV / exam code for a processed sheet, so a misread code can be
// fixed in place instead of leaving the sheet stuck at "No key".
function resultFieldsHtml(record) {
  return `
    <div class="key-fields" style="margin-bottom:10px;">
      <label for="result-mssv">MSSV</label>
      <input id="result-mssv" data-result-mssv value="${escapeHtml(record.mssv || "")}" inputmode="numeric" placeholder="not detected" />
      <label for="result-code">Code</label>
      <input id="result-code" data-result-code value="${escapeHtml(record.examCode || "")}" inputmode="numeric" placeholder="not detected" />
    </div>
  `;
}

function qualityAlertHtml(record) {
  if (!lowQualityScan(record)) return "";
  const stats = countedAnswerStats(record);
  return `<div class="key-alert">Low-confidence scan: ${stats.suspicious}/${stats.questionCount} answers are unclear and ${stats.questionCount - stats.answered} are blank. The photo may be misaligned or poorly lit — check the preview and consider retaking it.</div>`;
}

function bindResultFields(record) {
  const mssvInput = els.detailPanel.querySelector("[data-result-mssv]");
  const codeInput = els.detailPanel.querySelector("[data-result-code]");
  if (!mssvInput || !codeInput) return;
  const apply = () => {
    record.mssv = mssvInput.value.trim() || null;
    record.examCode = codeInput.value.trim();
    applyScore(record);
    renderResults();
    renderReviewList();
    updateActions();
    persistState();
  };
  mssvInput.addEventListener("input", apply);
  codeInput.addEventListener("input", apply);
  // Re-render the detail (badge, subtitle, overlay) once editing is done.
  const refresh = () => {
    renderRecordPreview(record, "result");
    renderDetail(record, "result");
  };
  mssvInput.addEventListener("change", refresh);
  codeInput.addEventListener("change", refresh);
}

// Two visible states only: "correct" (green) and "suspend" (yellow).
// Everything else — normal answered, blank, manual — stays neutral.
// "ignored" (dashed) marks cells outside the active Questions range.
function answerCellClass(record, type, idx, answer) {
  if (type === "key") {
    const analysis = analyzeKey(record);
    if (idx + 1 > analysis.questionCount) return "ignored";
    // A blank or auto-recovered key answer needs the grader's eyes.
    if (!answer.selected.length || answer.recovered) return "suspend";
    return "";
  }
  if (idx + 1 > getQuestionCount(record)) return "ignored";
  if (!answer.selected.length) return "";
  if (type === "result") {
    const key = keyForRecord(record);
    if (key && key.masks[idx] !== 0 && record.masks[idx] === key.masks[idx]) return "correct";
    if (answer.suspicious || answer.selected.length > 1) return "suspend";
  }
  return "";
}

function answerEditorHtml(record, type) {
  const editor = state.answerEditor;
  if (!editor || editor.record !== record || editor.type !== type) return "";
  const idx = editor.index;
  const answer = record.answers[idx] || { selected: [] };
  const current = answer.selected || [];
  return `
    <div class="answer-editor" data-editor-idx="${idx}">
      <div class="answer-editor-title">
        <span>Q${idx + 1}</span>
        <span>${escapeHtml(current.length ? current.join("+") : "blank")}</span>
      </div>
      <div class="answer-editor-options">
        ${letters.map((letter) => `
          <button class="choice-button ${current.includes(letter) ? "active" : ""}" type="button" data-choice="${letter}">
            ${letter}
          </button>
        `).join("")}
        <button class="choice-button clear" type="button" data-choice="">Clear</button>
      </div>
    </div>
  `;
}

function bindAnswerGrid(record, type) {
  Array.from(els.detailPanel.querySelectorAll("[data-answer-idx]")).forEach((button) => {
    button.addEventListener("click", () => {
      const idx = Number(button.dataset.answerIdx);
      if (idx + 1 > getQuestionCount(record)) {
        setStatus(`Q${idx + 1} is outside Questions. Change Questions to edit it.`, "warn");
        return;
      }
      state.answerEditor = { record, type, index: idx };
      renderDetail(record, type);
    });
  });

  const editor = els.detailPanel.querySelector("[data-editor-idx]");
  if (!editor) return;
  const idx = Number(editor.dataset.editorIdx);
  Array.from(editor.querySelectorAll("[data-choice]")).forEach((button) => {
    button.addEventListener("click", () => {
      const choice = button.dataset.choice;
      const mask = choice ? lettersToMask([choice]) : 0;
      editAnswer(record, type, idx, mask);
    });
  });
}

function editAnswer(record, type, idx, mask) {
  setAnswerMask(record, idx, mask, { manual: true });
  record.masks = record.answers.map((answer) => answer.mask || 0);
  record.answeredCount = record.masks.filter((item) => item !== 0).length;
  if (type === "key") {
    record.manualDraft = manualDraftForKey(record);
    record.manualError = null;
    recomputeScores();
    renderKeys();
    setStatus(`Updated key Q${idx + 1}`, "ok");
  } else {
    applyScore(record);
    setStatus(`Updated ${record.fileName} Q${idx + 1}`, "ok");
  }
  renderResults();
  renderReviewList();
  renderRecordPreview(record, type);
  renderDetail(record, type);
  updateActions();
  persistState();
}

function renderReviewList() {
  if (!els.reviewList) return;
  const reviewRows = state.results.filter((row) => row.status !== "OK");
  if (reviewRows.length === 0) {
    els.reviewList.innerHTML = `<div class="empty compact">No sheets need attention.</div>`;
    return;
  }
  els.reviewList.innerHTML = reviewRows.map((row, idx) => `
    <button class="review-item" type="button" data-review-idx="${idx}">
      <span title="${escapeHtml(row.fileName)}">${escapeHtml(row.fileName)}</span>
      ${statusBadge(row.status)}
    </button>
  `).join("");
  Array.from(els.reviewList.querySelectorAll(".review-item")).forEach((button, idx) => {
    button.addEventListener("click", () => selectRecord(reviewRows[idx], "result", { scroll: true }));
  });
}

async function scanKeys() {
  if (!state.ready || state.keyFiles.length === 0) return;
  if (!syncQuestionCountFromInput()) {
    setStatus("Set Questions between 1 and 60 before scanning", "bad");
    return;
  }
  state.keys = [];
  renderKeys();
  setStatus(`Scanning ${state.questionCount}-question answer keys...`);
  setProgress(0, state.keyFiles.length);
  for (let i = 0; i < state.keyFiles.length; i++) {
    const file = state.keyFiles[i];
    setStatus(`Scanning key ${i + 1}/${state.keyFiles.length}: ${file.name}`);
    const payload = await processFile(file, null);
    const key = makeRecord(file, payload, "key");
    state.keys.push(key);
    selectRecord(key, "key");
    renderKeys();
    setProgress(i + 1, state.keyFiles.length);
  }
  const needsReview = state.keys.filter((key) => {
    const analysis = analyzeKey(key);
    return analysis.holes.length > 0 || analysis.recovered.length > 0;
  }).length;
  const needsCode = state.keys.filter((key) => !key.examCode.trim()).length;
  const parts = [`Scanned ${state.keys.length} answer key${state.keys.length === 1 ? "" : "s"}`];
  if (needsCode) parts.push(`${needsCode} need${needsCode === 1 ? "s" : ""} an exam code`);
  if (needsReview) parts.push(`${needsReview} need review`);
  setStatus(parts.join(", "), needsCode || needsReview ? "warn" : "ok");
  recomputeScores();
  renderResults();
  updateActions();
  persistState();
}

async function scanSheets() {
  if (!state.ready || state.sheetFiles.length === 0 || getReadyKeys().length === 0) return;
  if (!syncQuestionCountFromInput()) {
    setStatus("Set Questions between 1 and 60 before processing", "bad");
    return;
  }
  state.results = [];
  renderResults();
  setStatus("Processing student sheets...");
  setProgress(0, state.sheetFiles.length);

  for (let i = 0; i < state.sheetFiles.length; i++) {
    const file = state.sheetFiles[i];
    setStatus(`Processing ${i + 1}/${state.sheetFiles.length}: ${file.name}`);
    const payload = await processFile(file, null);
    const result = makeRecord(file, payload, "result");
    applyScore(result);
    state.results.push(result);
    selectRecord(result, "result");
    renderResults();
    setProgress(i + 1, state.sheetFiles.length);
    persistState();
  }

  const review = state.results.filter((r) => r.status !== "OK").length;
  setStatus(`Processed ${state.results.length} sheets${review ? `, ${review} need review` : ""}`, review ? "warn" : "ok");
  updateActions();
  persistState();
}

function processFile(file, groundTruth) {
  return new Promise((resolve, reject) => {
    const worker = state.worker;
    if (!worker) {
      reject(new Error("Worker not ready"));
      return;
    }

    const timeout = setTimeout(() => {
      worker.removeEventListener("message", handler);
      reject(new Error("Processing timed out"));
    }, 45000);

    const handler = (event) => {
      const msg = event.data;
      if (!msg) return;
      if (msg.type === OMR_MSG.SHEET_RESULT) {
        clearTimeout(timeout);
        worker.removeEventListener("message", handler);
        resolve(msg.payload);
      } else if (msg.type === OMR_MSG.ERROR) {
        clearTimeout(timeout);
        worker.removeEventListener("message", handler);
        reject(new Error(msg.error || "Processing failed"));
      }
    };

    worker.addEventListener("message", handler);
    worker.postMessage({
      type: OMR_MSG.PROCESS_SHEET,
      payload: { width: 0, height: 0, file, groundTruth }
    });
  }).catch((error) => {
    setStatus(error.message, "bad");
    return {
      status: 1,
      result: { answers: [], answeredCount: 0, suspiciousCount: 0, multiMarkCount: 0 },
      perf: {},
      error: error.message
    };
  });
}

function makeRecord(file, payload, type) {
  const result = payload.result || {};
  const answers = normalizeAnswers(result.answers || []);
  const masks = answers.map((answer) => answer.mask || 0);
  const bubbles = Array.isArray(result.bubbles) ? result.bubbles : [];
  const preview = payload.warpedPreview || payload.preview;
  const previewWidth = payload.previewWidth || 1700;
  const previewHeight = payload.previewHeight || 2400;
  let previewImage = null;
  let previewBuffer = null;
  if (preview) {
    previewBuffer = new Uint8ClampedArray(preview);
    previewImage = createPreviewImage(previewBuffer, previewWidth, previewHeight);
  }

  const record = {
    type,
    fileName: file.name,
    mssv: result.mssv || null,
    examCode: result.examCode || "",
    answeredCount: result.answeredCount || answers.filter((a) => a.mask !== 0).length,
    suspiciousCount: result.suspiciousCount || 0,
    multiMarkCount: result.multiMarkCount || 0,
    reviewCount: (result.suspiciousCount || 0) + (result.multiMarkCount || 0),
    answers,
    masks,
    bubbles,
    detectedAnswers: cloneAnswers(answers),
    detectedMasks: [...masks],
    score: null,
    rawScore: null,
    totalQuestions: null,
    status: type === "key" ? "Key" : "No key",
    previewImage,
    error: payload.error || null,
    perf: payload.perf || {}
  };
  if (type === "key") recoverKeyAnswers(record, previewBuffer, previewWidth, previewHeight);
  return record;
}

function recoverKeyAnswers(key, imageBuffer, width, height) {
  const questionCount = getQuestionCount(key);
  if (!questionCount || (!key.bubbles.length && !imageBuffer)) return;

  for (let idx = 0; idx < questionCount; idx++) {
    if ((key.masks[idx] || 0) !== 0) continue;
    if (!shouldRecoverKeyQuestion(key, idx, questionCount)) continue;
    const candidate = bestVisualBubbleCandidate(imageBuffer, width, height, idx + 1)
      || bestBubbleCandidate(key.bubbles, idx + 1);
    if (!candidate) continue;
    setAnswerMask(key, idx, 1 << candidate.choice, {
      recovered: true,
      confidence: candidate.confidence ?? candidate.margin,
      fillRatio: candidate.fillRatio ?? candidate.score
    });
  }
  key.masks = key.answers.map((answer) => answer.mask || 0);
  key.answeredCount = key.masks.filter((mask) => mask !== 0).length;
}

function shouldRecoverKeyQuestion(key, index, questionCount) {
  const rawMasks = key.detectedMasks || key.masks || [];
  const prevFilled = index > 0 && (rawMasks[index - 1] || 0) !== 0;
  const nextFilled = index + 1 < questionCount && (rawMasks[index + 1] || 0) !== 0;
  const isFirst = index === 0;
  const isLast = index === questionCount - 1;
  return (prevFilled && nextFilled) || (isFirst && nextFilled) || (isLast && prevFilled);
}

function bestBubbleCandidate(bubbles, question) {
  const row = bubbles
    .filter((bubble) => bubble.question === question)
    .map((bubble) => ({
      choice: bubble.choice,
      fillRatio: Number(bubble.fill_ratio) || 0
    }))
    .sort((a, b) => b.fillRatio - a.fillRatio);
  if (row.length < 5) return null;

  const best = row[0];
  const second = row[1];
  const mean = row.reduce((sum, item) => sum + item.fillRatio, 0) / row.length;
  const margin = best.fillRatio - second.fillRatio;

  // Product-only rescue for answer keys: when core leaves a key row blank,
  // choose the strongest bubble if it still stands out locally. This does not
  // change the paper/benchmark C++ pipeline.
  if (best.fillRatio < 0.08) return null;
  if (margin < 0.012 && best.fillRatio < mean + 0.025) return null;
  return { choice: best.choice, fillRatio: best.fillRatio, margin };
}

function bestVisualBubbleCandidate(buffer, width, height, question) {
  if (!buffer || !width || !height) return null;
  const candidates = [];
  for (let choice = 0; choice < letters.length; choice++) {
    const cell = answerCell(question, choice);
    if (!cell) continue;
    const best = localDarkScore(buffer, width, height, cell.cx, cell.cy, cell.radius);
    candidates.push({ choice, score: best.score, fillRatio: best.score, offsetX: best.dx, offsetY: best.dy });
  }
  if (candidates.length < 5) return null;

  candidates.sort((a, b) => b.score - a.score);
  const best = candidates[0];
  const second = candidates[1];
  const mean = candidates.reduce((sum, item) => sum + item.score, 0) / candidates.length;
  const confidence = best.score - second.score;

  if (best.score < 0.22) return null;
  if (confidence < 0.035 && best.score < mean + 0.06) return null;
  return { choice: best.choice, score: best.score, fillRatio: best.score, confidence };
}

function localDarkScore(buffer, width, height, cx, cy, radius) {
  const innerRadius = Math.max(7, Math.round(radius * 0.46));
  const searchRadius = Math.max(12, Math.round(radius * 0.85));
  const step = 4;
  let best = { score: 0, dx: 0, dy: 0 };

  for (let dy = -searchRadius; dy <= searchRadius; dy += step) {
    for (let dx = -searchRadius; dx <= searchRadius; dx += step) {
      const score = diskDarkness(buffer, width, height, Math.round(cx + dx), Math.round(cy + dy), innerRadius);
      if (score > best.score) best = { score, dx, dy };
    }
  }
  return best;
}

function diskDarkness(buffer, width, height, cx, cy, radius) {
  let sum = 0;
  let count = 0;
  const r2 = radius * radius;
  for (let y = cy - radius; y <= cy + radius; y++) {
    if (y < 0 || y >= height) continue;
    for (let x = cx - radius; x <= cx + radius; x++) {
      if (x < 0 || x >= width) continue;
      const dx = x - cx;
      const dy = y - cy;
      if (dx * dx + dy * dy > r2) continue;
      const offset = (y * width + x) * 4;
      const luminance = 0.2126 * buffer[offset] + 0.7152 * buffer[offset + 1] + 0.0722 * buffer[offset + 2];
      sum += Math.max(0, 235 - luminance) / 235;
      count += 1;
    }
  }
  return count ? sum / count : 0;
}

function normalizeAnswers(rawAnswers) {
  const out = [];
  for (let i = 0; i < 60; i++) {
    const raw = rawAnswers[i] || {};
    const selected = Array.isArray(raw.selected) ? raw.selected : maskToLetters(raw.mask || 0);
    out.push({
      q: raw.q || i + 1,
      mask: raw.mask || lettersToMask(selected),
      selected,
      suspicious: !!raw.suspicious,
      recovered: !!raw.recovered,
      manual: !!raw.manual,
      confidence: raw.confidence ?? null,
      fillRatio: raw.fillRatio ?? null
    });
  }
  return out;
}

function cloneAnswers(answers) {
  return answers.map((answer, idx) => ({
    q: answer.q || idx + 1,
    mask: answer.mask || 0,
    selected: [...(answer.selected || [])],
    suspicious: !!answer.suspicious,
    recovered: !!answer.recovered,
    manual: !!answer.manual,
    confidence: answer.confidence ?? null,
    fillRatio: answer.fillRatio ?? null
  }));
}

function maskToLetters(mask) {
  const selected = [];
  for (let i = 0; i < letters.length; i++) {
    if ((mask & (1 << i)) !== 0) selected.push(letters[i]);
  }
  return selected;
}

function lettersToMask(selected) {
  return selected.reduce((mask, letter) => {
    const idx = letters.indexOf(letter);
    return idx >= 0 ? mask | (1 << idx) : mask;
  }, 0);
}

function recomputeScores() {
  state.results.forEach(applyScore);
}

// Heuristic for a warp/photo failure: an unusually large share of unclear
// bubbles combined with unread answers. Tuned so clean batches (dataset_1)
// stay unflagged while misaligned dark-background shots (dataset_4) trip it.
function lowQualityScan(record) {
  const stats = countedAnswerStats(record);
  if (!stats.questionCount) return false;
  return stats.suspicious >= stats.questionCount * 0.45
    && stats.answered <= stats.questionCount * 0.93;
}

function applyScore(result) {
  const key = keyForRecord(result);
  const stats = countedAnswerStats(result);
  // "Review" is reserved for hard problems the teacher must resolve:
  // multi-marked answers or unreadable MSSV / exam code. Low-confidence
  // (suspicious) reads stay visible in the detail grid but do not flag
  // the sheet, otherwise every sheet ends up in review.
  result.reviewCount = stats.multi
    + (result.mssv ? 0 : 1)
    + (result.examCode ? 0 : 1);

  if (!key) {
    result.score = null;
    result.rawScore = null;
    result.totalQuestions = null;
    result.status = "No key";
    return;
  }

  let total = 0;
  let correct = 0;
  const questionCount = getQuestionCount(key);
  for (let i = 0; i < questionCount; i++) {
    const keyMask = key.masks[i] || 0;
    if (keyMask === 0) continue;
    total += 1;
    if ((result.masks[i] || 0) === keyMask) correct += 1;
  }

  result.rawScore = correct;
  result.totalQuestions = total;
  result.score = total > 0 ? Math.round((correct / total) * 1000) / 100 : null;
  result.status = lowQualityScan(result) ? "Check image"
    : result.reviewCount > 0 ? "Review"
    : "OK";
}

function keyForRecord(record) {
  return getReadyKeys().find((item) => item.examCode === record.examCode);
}

function createPreviewImage(buffer, width, height) {
  const src = document.createElement("canvas");
  src.width = width;
  src.height = height;
  src.getContext("2d").putImageData(new ImageData(buffer, width, height), 0, 0);
  const crop = detectPreviewCrop(buffer, width, height);
  const out = document.createElement("canvas");
  out.width = crop.width;
  out.height = crop.height;
  out.getContext("2d").drawImage(
    src,
    crop.x,
    crop.y,
    crop.width,
    crop.height,
    0,
    0,
    crop.width,
    crop.height
  );
  return {
    dataUrl: out.toDataURL("image/jpeg", 0.86),
    width: crop.width,
    height: crop.height,
    cropX: crop.x,
    cropY: crop.y,
    sourceWidth: width,
    sourceHeight: height
  };
}

function detectPreviewCrop(buffer, width, height) {
  const stride = 6;
  const threshold = 82;
  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;

  for (let y = 0; y < height; y += stride) {
    for (let x = 0; x < width; x += stride) {
      const offset = (y * width + x) * 4;
      const brightness = 0.2126 * buffer[offset] + 0.7152 * buffer[offset + 1] + 0.0722 * buffer[offset + 2];
      if (brightness > threshold) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }
  }

  if (maxX < minX || maxY < minY) {
    return { x: 0, y: 0, width, height };
  }

  const pad = 8;
  const x = Math.max(0, minX - pad);
  const y = Math.max(0, minY - pad);
  const right = Math.min(width, maxX + stride + pad);
  const bottom = Math.min(height, maxY + stride + pad);
  const cropWidth = right - x;
  const cropHeight = bottom - y;

  if (cropWidth < width * 0.35 || cropHeight < height * 0.35) {
    return { x: 0, y: 0, width, height };
  }
  return { x, y, width: cropWidth, height: cropHeight };
}

function renderRecordPreview(record, type) {
  const canvas = els.previewCanvas;
  const ctx = canvas.getContext("2d");
  const wrap = canvas.parentElement;
  if (!record || !record.previewImage) {
    canvas.width = 850;
    canvas.height = 1200;
    // Short placeholder on mobile — no point reserving a full-page blank box.
    wrap.style.setProperty("--preview-ar", "2 / 1");
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    return;
  }
  wrap.style.setProperty("--preview-ar", `${record.previewImage.width} / ${record.previewImage.height}`);

  const image = new Image();
  image.onload = () => {
    canvas.width = record.previewImage.width;
    canvas.height = record.previewImage.height;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
    drawAnswerOverlay(ctx, record, type);
  };
  image.src = record.previewImage.dataUrl;
}

function drawAnswerOverlay(ctx, record, type) {
  const key = type === "result" ? keyForRecord(record) : null;
  const offsetX = record.previewImage?.cropX || 0;
  const offsetY = record.previewImage?.cropY || 0;
  const questionCount = getQuestionCount(record) || record.answers.length;
  ctx.save();
  for (let i = 0; i < Math.min(record.answers.length, questionCount); i++) {
    const answer = record.answers[i];
    const keyMask = key?.masks[i] || 0;
    if (type === "result" && keyMask !== 0 && (record.masks[i] || 0) !== keyMask) {
      for (let choice = 0; choice < letters.length; choice++) {
        if ((keyMask & (1 << choice)) !== 0) {
          drawBubbleMark(ctx, i + 1, choice, offsetX, offsetY, "correct");
        }
      }
    }

    for (const selected of answer.selected) {
      const choice = letters.indexOf(selected);
      const isCorrectChoice = type === "result" && keyMask !== 0 && (keyMask & (1 << choice)) !== 0;
      const mode = isCorrectChoice ? "correct" : (type === "result" && keyMask !== 0 ? "wrong" : "detected");
      drawBubbleMark(ctx, i + 1, choice, offsetX, offsetY, mode);
    }
  }
  ctx.restore();
}

function drawBubbleMark(ctx, qNum, choice, offsetX, offsetY, mode) {
  const cell = answerCell(qNum, choice);
  if (!cell) return;
  const cx = cell.cx - offsetX;
  const cy = cell.cy - offsetY;
  if (cx < -cell.radius || cy < -cell.radius || cx > ctx.canvas.width + cell.radius || cy > ctx.canvas.height + cell.radius) {
    return;
  }

  const isCorrect = mode === "correct";
  const isWrong = mode === "wrong";
  if (isCorrect) {
    ctx.beginPath();
    ctx.fillStyle = "rgba(19, 138, 91, 0.24)";
    ctx.arc(cx, cy, cell.radius * 1.08, 0, Math.PI * 2);
    ctx.fill();
  }

  ctx.beginPath();
  ctx.lineWidth = isCorrect ? 6 : 5;
  ctx.setLineDash(isCorrect ? [10, 7] : []);
  ctx.strokeStyle = isCorrect
    ? "rgba(12, 122, 78, 0.98)"
    : isWrong
      ? "rgba(205, 48, 48, 0.96)"
      : "rgba(29, 95, 211, 0.95)";
  ctx.arc(cx, cy, cell.radius, 0, Math.PI * 2);
  ctx.stroke();
  ctx.setLineDash([]);
}

function answerCell(qNum, choice) {
  if (choice < 0) return null;
  const block = OMR_LAYOUT.blocks.find((item) => qNum >= item.start && qNum <= item.end);
  if (!block) return null;
  const row = qNum - block.start;
  const cellW = (block.x2 - block.x1) / block.cols;
  const cellH = (block.y2 - block.y1) / block.rows;
  return {
    cx: block.x1 + (choice + 0.5) * cellW,
    cy: block.y1 + (row + 0.5) * cellH,
    radius: Math.min(cellW, cellH) * 0.32
  };
}

function exportCsv() {
  const header = "file,mssv,exam_code,answered,suspicious,multi_mark,raw_score,total_questions,score,status\n";
  const rows = state.results.map((r) => {
    const stats = countedAnswerStats(r);
    return [
      csvCell(r.fileName),
      csvCell(r.mssv || ""),
      csvCell(r.examCode || ""),
      stats.answered,
      stats.suspicious,
      stats.multi,
      r.rawScore ?? "",
      r.totalQuestions ?? "",
      r.score === null ? "" : r.score.toFixed(2),
      csvCell(r.status)
    ].join(",");
  });
  downloadBlob(header + rows.join("\n") + "\n", "gradesnap-results.csv", "text/csv");
}

function exportJson() {
  const data = {
    engine: state.engine,
    exportedAt: new Date().toISOString(),
    keys: state.keys.map((key) => {
      const analysis = analyzeKey(key);
      return {
        fileName: key.fileName,
        examCode: key.examCode,
        questionCount: analysis.questionCount,
        answeredCount: analysis.answeredWithin,
        detectedCount: analysis.detectedCount,
        recoveredAnswers: recoveredAnswerStrings(key.answers),
        manualAnswers: manualAnswerStrings(key.answers),
        answers: answerStrings(key.answers)
      };
    }),
    results: state.results.map((r) => {
      const stats = countedAnswerStats(r);
      return {
        fileName: r.fileName,
        mssv: r.mssv,
        examCode: r.examCode,
        answeredCount: stats.answered,
        detectedCount: r.answeredCount,
        suspiciousCount: stats.suspicious,
        multiMarkCount: stats.multi,
        rawScore: r.rawScore,
        totalQuestions: r.totalQuestions,
        score: r.score,
        status: r.status,
        manualAnswers: manualAnswerStrings(r.answers),
        answers: answerStrings(r.answers)
      };
    })
  };
  downloadBlob(JSON.stringify(data, null, 2), "gradesnap-results.json", "application/json");
}

function answerStrings(answers) {
  const out = {};
  answers.forEach((answer, idx) => {
    out[`q${String(idx + 1).padStart(2, "0")}`] = answer.selected.join("+");
  });
  return out;
}

function recoveredAnswerStrings(answers) {
  const out = {};
  answers.forEach((answer, idx) => {
    if (answer.recovered) {
      out[`q${String(idx + 1).padStart(2, "0")}`] = {
        answer: answer.selected.join("+"),
        fillRatio: answer.fillRatio,
        confidence: answer.confidence
      };
    }
  });
  return out;
}

function manualAnswerStrings(answers) {
  const out = {};
  answers.forEach((answer, idx) => {
    if (answer.manual) {
      out[`q${String(idx + 1).padStart(2, "0")}`] = answer.selected.join("+");
    }
  });
  return out;
}

function downloadBlob(content, fileName, type) {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

function csvCell(value) {
  return `"${String(value).replace(/"/g, '""')}"`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

els.questionCountInput.addEventListener("change", () => syncQuestionCountFromInput(true));

els.keyInput.addEventListener("change", (event) => {
  state.keyFiles = Array.from(event.target.files || []);
  state.keys = [];
  updateCounts();
  renderKeys();
  updateActions();
  setProgress(0, 0);
  setStatus(state.keyFiles.length && state.ready ? "Answer keys selected" : readyStatusText(), readyStatusKind());
});

els.sheetInput.addEventListener("change", (event) => {
  state.sheetFiles = Array.from(event.target.files || []);
  updateCounts();
  updateActions();
  setStatus(state.sheetFiles.length && state.ready ? "Student sheets selected" : readyStatusText(), readyStatusKind());
});

els.scanKeysBtn.addEventListener("click", () => scanKeys());
els.scanSheetsBtn.addEventListener("click", () => scanSheets());

// Export is a split button: the main button toggles a small CSV/JSON menu.
function closeExportMenu() {
  els.exportMenu.hidden = true;
  els.exportBtn.setAttribute("aria-expanded", "false");
}
els.exportBtn.addEventListener("click", (event) => {
  event.stopPropagation();
  if (els.exportBtn.disabled) return;
  const willOpen = els.exportMenu.hidden;
  els.exportMenu.hidden = !willOpen;
  els.exportBtn.setAttribute("aria-expanded", String(willOpen));
});
els.exportMenu.addEventListener("click", (event) => event.stopPropagation());
document.addEventListener("click", () => closeExportMenu());
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeExportMenu();
});
els.exportCsvBtn.addEventListener("click", () => { exportCsv(); closeExportMenu(); });
els.exportJsonBtn.addEventListener("click", () => { exportJson(); closeExportMenu(); });
els.clearSessionBtn.addEventListener("click", () => {
  if (state.keys.length === 0 && state.results.length === 0) return;
  if (window.confirm("Clear all scanned keys and results? This cannot be undone.")) {
    clearSession();
  }
});
function bindToggle(button, panel) {
  button.addEventListener("click", () => {
    panel.classList.toggle("collapsed");
    button.classList.toggle("collapsed");
  });
}

bindToggle(els.answerReviewToggle, els.answerReviewPanel);

updateCounts();
renderKeys();
renderResults();
renderReviewList();
setProgress(0, 0);
(async () => {
  const restored = await restoreSession();
  const ok = await initWorker();
  if (ok && restored) {
    setStatus(
      `${engineLabel()} ready — restored ${state.keys.length} key(s), ${state.results.length} sheet(s)`,
      "ok"
    );
  }
})();
