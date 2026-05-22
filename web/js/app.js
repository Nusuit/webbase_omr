/** 
 * OMR Web-based Edge Computing Benchmarker
 * Branch: testing (CV vs YOLO side-by-side)
 */

let workerCV, workerYOLO;
let readyCV = false, readyYOLO = false;

let sheetFiles = [];

let keyFiles = [];
let pendingGroundTruthsCV = [];
let pendingGroundTruthsYOLO = [];
let globalGroundTruthsCV = [];
let globalGroundTruthsYOLO = [];

let testRuns = 1;
let currentRun = 0;

const Metrics = {
  CV: { stats: [], bubbles: [] },
  YOLO: { stats: [], bubbles: [] }
};

function checkAndInit() {
  const infoCV = document.getElementById("infoCV");
  const infoYOLO = document.getElementById("infoYOLO");
  const platformStr = `Screen: ${window.innerWidth}×${window.innerHeight} <br/> Browser: ${navigator.userAgent.substring(0,60)}...`;
  
  infoCV.innerHTML = platformStr;
  infoYOLO.innerHTML = platformStr;

  workerCV = new Worker("./js/worker-cv.js?v=" + Date.now());
  workerYOLO = new Worker("./js/worker-yolo.js?v=" + Date.now());

  const onMessage = (tag) => (e) => {
    const msg = e.data;
    if (msg.type === OMR_MSG.READY) {
      if (tag === 'CV') { readyCV = true; document.getElementById("statusCV").classList.add("ready"); infoCV.innerHTML += "<br/>WASM: Loaded"; }
      if (tag === 'YOLO') { readyYOLO = true; document.getElementById("statusYOLO").classList.add("ready"); infoYOLO.innerHTML += "<br/>WebGPU/WASM: Loaded"; }
      checkReady();
    }
  };

  workerCV.onmessage = onMessage('CV');
  workerYOLO.onmessage = onMessage('YOLO');

  workerCV.postMessage({ type: OMR_MSG.INIT });
  workerYOLO.postMessage({ type: OMR_MSG.INIT });
}

function checkReady() {
  if (readyCV && readyYOLO) {
    const hasValidKeyCV = globalGroundTruthsCV.length > 0 && globalGroundTruthsCV.every(k => k !== null);
    const hasValidKeyYOLO = globalGroundTruthsYOLO.length > 0 && globalGroundTruthsYOLO.every(k => k !== null);
    if (sheetFiles.length > 0 && hasValidKeyCV && hasValidKeyYOLO) {
      document.getElementById("submitBtn").disabled = false;
      document.getElementById("submitBtn").innerText = "🚀 Run Batch Benchmark";
    } else {
      document.getElementById("submitBtn").disabled = true;
    }
  }
}

// UI Handlers
document.getElementById("uploadKey").onchange = async (e) => {
  if (!e.target.files || e.target.files.length === 0) return;
  keyFiles = Array.from(e.target.files);
  if (keyFiles.length === 1) {
    document.getElementById("keyName").innerText = keyFiles[0].name;
  } else {
    document.getElementById("keyName").innerText = `${keyFiles.length} keys`;
  }
  
  document.getElementById("keyWarningBox").innerText = `Analyzing ${keyFiles.length} Answer Key(s) on CV and YOLO...`;
  document.getElementById("keyWarningBox").style.color = "var(--warn)";
  document.getElementById("keyTableContentCV").innerHTML = "";
  document.getElementById("keyTableContentYOLO").innerHTML = "";
  document.getElementById("keyPreviewModal").style.display = "flex";
  document.getElementById("confirmKeyBtn").style.display = "none";
  document.getElementById("submitBtn").disabled = true;
  globalGroundTruthsCV = [];
  globalGroundTruthsYOLO = [];
  pendingGroundTruthsCV = [];
  pendingGroundTruthsYOLO = [];

  let validKeysCount = 0;

  for (let k = 0; k < keyFiles.length; k++) {
    const file = keyFiles[k];
    const bmp = await createImageBitmap(file);

    const processKey = (worker, method, contentBoxId, pendingArray) => {
        return new Promise((resolve) => {
          const handler = (msg_e) => {
            const msg = msg_e.data;
            if (msg.type === OMR_MSG.SHEET_RESULT) {
              worker.removeEventListener('message', handler);
              const res = msg.payload.result;
              
              let html = `<div style="margin-bottom: 12px; font-weight: bold; border-bottom: 1px solid var(--border); padding-bottom: 4px;">Key ${k+1}: ${file.name}</div>`;
              const letters = ["A", "B", "C", "D", "E"];
              let hasMissing = false;
              const maskArray = [];
              
              res.answers.forEach((ans, idx) => {
                let qStr = `Q${(idx+1).toString().padStart(2, '0')}:  `;
                for(let c=0; c<5; c++) {
                   let b = res.bubbles.find(bub => bub.question === ans.q && bub.choice === c);
                   if ((ans.mask & (1<<c)) !== 0) qStr += `<span style="color:var(--accent); font-weight:bold;">${letters[c]} ●</span> (${b.fill_ratio.toFixed(2)}) `;
                   else qStr += `${letters[c]} ○ (${b.fill_ratio.toFixed(2)}) `;
                }
                qStr += " →  ";
                
                if (ans.mask === 0) {
                   qStr += `<span style="color:var(--text-muted);">Empty (Unanswered)</span>`;
                } else {
                   const selectedStr = ans.selected.join("+");
                   qStr += `${selectedStr}`;
                   if (ans.selected.length > 1) qStr += " <span style='color:var(--warn)'>(MULTI-MARK)</span>";
                }
                
                maskArray.push(ans.mask);
                html += `<div>${qStr}</div>`;
              });
              
              document.getElementById(contentBoxId).innerHTML += `<div style="margin-bottom: 24px;">${html}</div>`;
              
              if (msg.payload.preview && method === 'CV') {
                 // Sometime we just need to render ONE preview, CV is enough.
                 renderPreview('previewCV', new Uint8ClampedArray(msg.payload.preview), msg.payload.previewWidth, msg.payload.previewHeight);
              }
              if (msg.payload.preview && method === 'YOLO') {
                 renderPreview('previewYOLO', new Uint8ClampedArray(msg.payload.preview), msg.payload.previewWidth, msg.payload.previewHeight);
              }
              
              pendingArray.push(maskArray);
              resolve({ hasMissing });
              
            } else if (msg.type === OMR_MSG.ERROR) {
              worker.removeEventListener('message', handler);
              document.getElementById(contentBoxId).innerHTML += `<div style="color:var(--warn)">Key ${k+1} error: ${msg.error}</div>`;
              pendingArray.push(null);
              resolve({ hasMissing: true });
            }
          };
          
          worker.addEventListener('message', handler);
          worker.postMessage({ type: OMR_MSG.PROCESS_SHEET, payload: { width: bmp.width, height: bmp.height, file: file, groundTruth: null } });
        });
    };

    const [resCV, resYOLO] = await Promise.all([
       processKey(workerCV, 'CV', 'keyTableContentCV', pendingGroundTruthsCV),
       processKey(workerYOLO, 'YOLO', 'keyTableContentYOLO', pendingGroundTruthsYOLO)
    ]);
    validKeysCount++;
  }

  if (validKeysCount === keyFiles.length && validKeysCount > 0) {
    document.getElementById("keyWarningBox").innerText = `⚠️ Vui lòng đối chiếu 2 cột dữ liệu bọt khí phía trên với ảnh Preview nền mờ ở sau. (Q trống/Empty cho bài <60 câu là bình thường). Nếu khớp, click Approve Ground Truth.`;
    document.getElementById("keyWarningBox").style.color = "var(--warn)";
    document.getElementById("confirmKeyBtn").style.display = "inline-block";
  } else {
    document.getElementById("keyWarningBox").innerText = `❌ Đã có lỗi xảy ra trong quá trình quét Key.`;
    document.getElementById("keyWarningBox").style.color = "#ef4444";
  }
};

document.getElementById("confirmKeyBtn").onclick = () => {
   globalGroundTruthsCV = [...pendingGroundTruthsCV];
   globalGroundTruthsYOLO = [...pendingGroundTruthsYOLO];
   document.getElementById("keyPreviewModal").style.display = "none";
   checkReady();
};
document.getElementById("uploadSheet").onchange = async (e) => {
  if (!e.target.files || e.target.files.length === 0) return;
  sheetFiles = Array.from(e.target.files);
  if (sheetFiles.length === 1) {
    document.getElementById("sheetName").innerText = sheetFiles[0].name;
  } else {
    document.getElementById("sheetName").innerText = `${sheetFiles.length} files`;
  }
  checkReady();
};

document.getElementById("submitBtn").onclick = async () => {
  const isBench = document.getElementById("benchToggle").checked;
  testRuns = isBench ? parseInt(document.getElementById("benchRuns").value, 10) || 1 : 1;
  Metrics.CV.stats = [];
  Metrics.CV.bubbles = [];
  Metrics.YOLO.stats = [];
  Metrics.YOLO.bubbles = [];
  
  document.getElementById("logCV").innerHTML = "";
  document.getElementById("logYOLO").innerHTML = "";
  document.getElementById("submitBtn").disabled = true;

  // Run Sequential iterations
  let totalRuns = testRuns * sheetFiles.length;
  let counter = 0;
  
  const progBox = document.getElementById("benchProgress");
  progBox.innerText = `Process: 0/${totalRuns}`;

  for (let i = 0; i < testRuns; i++) {
    for (let j = 0; j < sheetFiles.length; j++) {
      counter++;
      currentRun = counter;
      progBox.innerText = `Process: ${counter}/${totalRuns}`;

      const file = sheetFiles[j];
      const gtIdxCV = Math.min(j, globalGroundTruthsCV.length - 1);
      const gtIdxYOLO = Math.min(j, globalGroundTruthsYOLO.length - 1);
      
      const p1 = runPipeline('CV', file, globalGroundTruthsCV[gtIdxCV]);
      const p2 = runPipeline('YOLO', file, globalGroundTruthsYOLO[gtIdxYOLO]);
      
      await Promise.all([p1, p2]);
      await new Promise(r => setTimeout(r, 50)); // Render UI fast tick
    }
  }

  progBox.innerText = `✅ Completed ${totalRuns}`;

  if (totalRuns > 1) {
    printBenchmarkSummary('CV', Metrics.CV.stats, totalRuns);
    printBenchmarkSummary('YOLO', Metrics.YOLO.stats, totalRuns);
  }

  document.getElementById("submitBtn").disabled = false;
};

// Pipeline Dispatch
function runPipeline(method, file, gt) {
  return new Promise((resolve, reject) => {
    const worker = method === 'CV' ? workerCV : workerYOLO;
    const logBox = document.getElementById(method === 'CV' ? 'logCV' : 'logYOLO');
    const start_time = performance.now();
    let jsHeapStart = 0;
    if (performance.memory) jsHeapStart = performance.memory.usedJSHeapSize;

    appendLog(logBox, method, 0, `Pipeline START — file: ${file.name}`);

    const handler = (e) => {
      const msg = e.data;
      if (msg.type === OMR_MSG.SHEET_RESULT) {
        worker.removeEventListener('message', handler);
        const end_time = performance.now();
        const e2e_ms = end_time - start_time;
        const perf = msg.payload.perf;
        const result = msg.payload.result;
        
        let jsHeapEnd = 0;
        let heapUsageMb = 0;
        let heapTotalMb = 0;
        if (performance.memory) {
          jsHeapEnd = performance.memory.usedJSHeapSize;
          heapUsageMb = jsHeapEnd / (1024 * 1024);
          heapTotalMb = performance.memory.jsHeapSizeLimit / (1024 * 1024);
        }

        // Print internal logs accurately using offset
        if (perf.cpp_logs) {
          perf.cpp_logs.forEach(l => {
            appendLog(logBox, method, l.ts, l.msg.trim());
          });
        }
        
        if (method === 'YOLO') {
          appendLog(logBox, method, perf.yolo_ms, `WebGPU Inference / YOLO stage — ${perf.yolo_ms.toFixed(1)}ms`);
        }

        appendLog(logBox, method, e2e_ms, `JS Heap: ${heapUsageMb.toFixed(1)}MB used / ${heapTotalMb.toFixed(1)}MB limit`);
        appendLog(logBox, method, e2e_ms, `WASM Heap: ${(perf.wasm_heap_after / (1024*1024)).toFixed(1)} MB`);
        appendLog(logBox, method, e2e_ms, `Pipeline END — E2E: ${e2e_ms.toFixed(1)}ms`);
        
        let scoreStr = result.answeredCount ? `${result.answeredCount}/60` : "Unknown";
        appendLog(logBox, method, e2e_ms, `── RESULT: ${scoreStr} ──`);
        
        // Benchmark Mode
        appendLog(logBox, method, e2e_ms, `Bubble stats — TP:${result.tp} FP:${result.fp} FN:${result.fn} TN:${result.tn}`);
        const pr = result.tp / (result.tp + result.fp || 1);
        const rc = result.tp / (result.tp + result.fn || 1);
        const f1 = (2 * pr * rc) / (pr + rc || 1);
        appendLog(logBox, method, e2e_ms, `Precision: ${pr.toFixed(4)} Recall: ${rc.toFixed(4)} F1: ${f1.toFixed(4)}`);
        if (result.suspiciousCount > 0) {
          appendLog(logBox, method, e2e_ms, `Suspicious bubbles: ${result.suspiciousCount}`);
        }
        if (result.multiMarkCount > 0) {
          appendLog(logBox, method, e2e_ms, `Multi-mark detected: ${result.multiMarkCount} questions with >1 bubble filled`);
        }

        // Record metrics
        Metrics[method].stats.push({
          run: currentRun,
          e2e_ms: e2e_ms,
          inference_ms: method === 'YOLO' ? perf.yolo_ms : perf.cpp_ms,
          cpp_ms: perf.cpp_ms,
          js_heap_mb: heapUsageMb,
          wasm_heap_mb: perf.wasm_heap_after / (1024*1024),
          wasm_variant: perf.wasm_variant || "baseline",
          tp: result.tp, fp: result.fp, tn: result.tn, fn: result.fn,
          precision: pr, recall: rc, f1: f1,
          suspicious_count: result.suspiciousCount,
          multi_mark_count: result.multiMarkCount,
          total_bubbles: result.bubbles.length
        });
        
        // Add bubbles raw dump for this run
        if (result.bubbles) {
          result.bubbles.forEach(b => {
             Metrics[method].bubbles.push(Object.assign({ run: currentRun }, b));
          });
        }

        // Paint preview
        if (msg.payload.preview) {
          renderPreview(method === 'CV' ? 'previewCV' : 'previewYOLO', new Uint8ClampedArray(msg.payload.preview), msg.payload.previewWidth, msg.payload.previewHeight);
        }

        resolve();
      } else if (msg.type === OMR_MSG.ERROR) {
        worker.removeEventListener('message', handler);
        appendLog(logBox, method, performance.now() - start_time, `ERROR: ${msg.error}`);
        resolve(); // resolve so we don't block Promise.all
      }
    };

    worker.addEventListener('message', handler);
    worker.postMessage({ type: OMR_MSG.PROCESS_SHEET, payload: { width: 0, height: 0, file: file, groundTruth: gt } });
  });
}

// Utilities
let scrollPending = {};
function appendLog(element, method, ts_offset, msg) {
  const tsStr = `[T+${Math.round(ts_offset)}ms]`.padEnd(12, ' ');
  const pStr = `[${method}]`.padEnd(6, ' ');
  const line = `${tsStr} ${pStr} ${msg}\n`;
  element.appendChild(document.createTextNode(line));
  
  if (!scrollPending[element.id]) {
    scrollPending[element.id] = true;
    requestAnimationFrame(() => {
      element.scrollTop = element.scrollHeight;
      scrollPending[element.id] = false;
    });
  }
}

function calcStats(arr) {
  if (arr.length === 0) return { mean: 0, sd: 0, min: 0, max: 0 };
  const sum = arr.reduce((a, b) => a + b, 0);
  const mean = sum / arr.length;
  const sqDiffs = arr.map(v => Math.pow(v - mean, 2));
  // Sample standard deviation (ddof=1)
  const variance = arr.length > 1 ? sqDiffs.reduce((a, b) => a + b, 0) / (arr.length - 1) : 0;
  const sd = Math.sqrt(variance);
  return { mean, sd, min: Math.min(...arr), max: Math.max(...arr) };
}

function printBenchmarkSummary(method, metricsData, totalRuns) {
  const logBox = document.getElementById(method === 'CV' ? 'logCV' : 'logYOLO');
  logBox.appendChild(document.createTextNode('\n'));

  const e2e   = calcStats(metricsData.map(m => m.e2e_ms));
  const infer = calcStats(metricsData.map(m => m.inference_ms));
  const cpps  = calcStats(metricsData.map(m => m.cpp_ms));
  const heap  = calcStats(metricsData.map(m => m.js_heap_mb));

  const tp = calcStats(metricsData.map(m => m.tp));
  const fp = calcStats(metricsData.map(m => m.fp));
  const tn = calcStats(metricsData.map(m => m.tn));
  const fn = calcStats(metricsData.map(m => m.fn));
  const pr = calcStats(metricsData.map(m => m.precision));
  const rc = calcStats(metricsData.map(m => m.recall));
  const f1 = calcStats(metricsData.map(m => m.f1));

  const p = (msg) => { logBox.appendChild(document.createTextNode(`[BENCHMARK] ${msg}\n`)); };

  const variants = [...new Set(metricsData.map(m => m.wasm_variant))];
  const coi = typeof window.crossOriginIsolated !== 'undefined'
    ? String(window.crossOriginIsolated) : 'unknown';

  // ── Detailed output (unchanged) ────────────────────────────────────────────
  p(`Runs completed: N=${totalRuns}  |  WASM variant: ${variants.join(", ")}  |  crossOriginIsolated: ${coi}`);
  p(`E2E        — mean=${e2e.mean.toFixed(1)}ms, SD=${e2e.sd.toFixed(2)}ms, min=${e2e.min.toFixed(1)}ms, max=${e2e.max.toFixed(1)}ms`);

  if (method === 'YOLO') {
    p(`YOLO Infer — mean=${infer.mean.toFixed(1)}ms, SD=${infer.sd.toFixed(2)}ms`);
    p(`C++ OMR    — mean=${cpps.mean.toFixed(1)}ms, SD=${cpps.sd.toFixed(2)}ms`);
  } else {
    p(`CV E2E     — mean=${cpps.mean.toFixed(1)}ms, SD=${cpps.sd.toFixed(2)}ms`);
  }

  p(`JS Heap    — mean=${heap.mean.toFixed(1)}MB, SD=${heap.sd.toFixed(2)}MB`);

  p(`Bubble-level aggregates (N=${totalRuns} total scans):`);
  p(`TP mean±SD: ${tp.mean.toFixed(1)}±${tp.sd.toFixed(2)}   FP: ${fp.mean.toFixed(1)}±${fp.sd.toFixed(2)}`);
  p(`TN mean±SD: ${tn.mean.toFixed(1)}±${tn.sd.toFixed(2)}   FN: ${fn.mean.toFixed(1)}±${fn.sd.toFixed(2)}`);
  p(`Precision:  ${pr.mean.toFixed(4)}±${pr.sd.toFixed(4)}`);
  p(`Recall:     ${rc.mean.toFixed(4)}±${rc.sd.toFixed(4)}`);
  p(`F1 Score:   ${f1.mean.toFixed(4)}±${f1.sd.toFixed(4)}`);

  p(`Platform   — ${navigator.userAgent}`);
  p(`Timestamp  — ${new Date().toISOString()}`);

  // ── Thermal throttle analysis ──────────────────────────────────────────────
  // Checkpoints: frame 1 (cold), 5, 10, 50, 100 — reveals SoC throttling ramp.
  const thermalFrames = [1, 5, 10, 50, 100].filter(n => n <= metricsData.length);
  if (thermalFrames.length >= 2) {
    logBox.appendChild(document.createTextNode('\n'));
    p(`THERMAL THROTTLE — E2E per frame (cold → sustained load)`);
    thermalFrames.forEach(n => {
      const m = metricsData[n - 1];
      if (!m) return;
      const inferStr = method === 'YOLO' ? `  YOLO=${m.inference_ms.toFixed(0)}ms` : '';
      p(`  Frame ${String(n).padStart(3)}: E2E=${m.e2e_ms.toFixed(0)}ms${inferStr}  C++=${m.cpp_ms.toFixed(0)}ms`);
    });
    const delta = metricsData[metricsData.length - 1].e2e_ms - metricsData[0].e2e_ms;
    const sign  = delta >= 0 ? '+' : '';
    const note  = Math.abs(delta) > 150 ? '  ⚠ THROTTLE DETECTED' : '  (nominal)';
    p(`  last-vs-first delta: ${sign}${delta.toFixed(0)}ms${note}`);
  }

  // ── Paper-ready compact block ──────────────────────────────────────────────
  logBox.appendChild(document.createTextNode('\n'));
  p(`──────────── PAPER-READY SUMMARY (paste into table) ────────────`);
  p(`Runs completed: N=${totalRuns}`);
  if (method === 'CV') {
    p(`CV E2E mean=${e2e.mean.toFixed(0)}ms SD=${e2e.sd.toFixed(1)}ms min=${e2e.min.toFixed(0)}ms max=${e2e.max.toFixed(0)}ms`);
    p(`C++ OMR mean=${cpps.mean.toFixed(0)}ms SD=${cpps.sd.toFixed(1)}ms`);
  } else {
    p(`YOLO E2E mean=${e2e.mean.toFixed(0)}ms SD=${e2e.sd.toFixed(1)}ms min=${e2e.min.toFixed(0)}ms max=${e2e.max.toFixed(0)}ms`);
    p(`YOLO Infer mean=${infer.mean.toFixed(0)}ms SD=${infer.sd.toFixed(1)}ms`);
    p(`C++ OMR mean=${cpps.mean.toFixed(0)}ms SD=${cpps.sd.toFixed(1)}ms`);
  }
  p(`Platform — ${navigator.userAgent}`);
  p(`────────────────────────────────────────────────────────────────`);

  logBox.scrollTop = logBox.scrollHeight;
}

function renderPreview(canvasId, buffer, w, h) {
  const canvas = document.getElementById(canvasId);
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  const imgData = new ImageData(buffer, w, h);
  ctx.putImageData(imgData, 0, 0);
}

// Copy & Clear & Export handlers
['CV', 'YOLO'].forEach(method => {
  document.getElementById(`clear${method === 'CV' ? 'Cv' : 'Yolo'}Btn`).onclick = () => {
    document.getElementById(`log${method}`).innerHTML = "";
  };
  
  document.getElementById(`copy${method === 'CV' ? 'Cv' : 'Yolo'}Btn`).onclick = () => {
    const text = document.getElementById(`log${method}`).innerText;
    navigator.clipboard.writeText(text);
  };
  
  document.getElementById(`export${method === 'CV' ? 'Cv' : 'Yolo'}Btn`).onclick = () => {
    const data = Metrics[method].stats;
    if (!data || data.length === 0) return;
    let csv = "run_index,method,wasm_variant,e2e_ms,inference_ms,cpp_omr_ms,js_heap_mb,wasm_heap_mb,tp,fp,fn,tn,precision,recall,f1,suspicious_count,multi_mark_count,total_bubbles,platform,timestamp\n";
    const ts = new Date().toISOString();
    const plat = navigator.userAgent;
    data.forEach(r => {
      csv += `${r.run},${method},${r.wasm_variant || "baseline"},${r.e2e_ms.toFixed(2)},${r.inference_ms.toFixed(2)},${r.cpp_ms.toFixed(2)},${r.js_heap_mb.toFixed(2)},${r.wasm_heap_mb.toFixed(2)},`;
      csv += `${r.tp},${r.fp},${r.fn},${r.tn},${r.precision.toFixed(4)},${r.recall.toFixed(4)},${r.f1.toFixed(4)},`;
      csv += `${r.suspicious_count},${r.multi_mark_count},${r.total_bubbles},`;
      csv += `"${plat}",${ts}\n`;
    });
    
    const blob = new Blob([csv], {type: "text/csv"});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `benchmark_stats_${method}_n${data.length}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  document.getElementById(`exportBubble${method === 'CV' ? 'Cv' : 'Yolo'}Btn`).onclick = () => {
    const data = Metrics[method].bubbles;
    if (!data || data.length === 0) return;
    let csv = "run_index,method,question,choice,detected,ground_truth,fill_ratio,suspicious,platform,timestamp\n";
    const ts = new Date().toISOString();
    const plat = navigator.userAgent;
    data.forEach(r => {
      csv += `${r.run},${method},${r.question},${r.choice},${r.detected},${r.ground_truth},${r.fill_ratio.toFixed(4)},${r.suspicious},"${plat}",${ts}\n`;
    });
    
    const blob = new Blob([csv], {type: "text/csv"});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `benchmark_bubbles_${method}_n${testRuns * sheetFiles.length}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };
});

window.onload = checkAndInit;
