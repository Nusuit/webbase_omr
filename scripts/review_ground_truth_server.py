#!/usr/bin/env python3
"""Local one-sheet-at-a-time ground-truth review UI.

The server edits `ground_truth_review_template.csv` in place. It is intended for
human verification of pre-labels, not for generating ground truth unattended.

Usage:
  python scripts/review_ground_truth_server.py
  python scripts/review_ground_truth_server.py --port 8092
"""
from __future__ import annotations

import argparse
import csv
import json
import mimetypes
import os
import tempfile
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse


REPO = Path(__file__).resolve().parent.parent
DEFAULT_REVIEW_DIR = REPO / "runs" / "accuracy_eval" / "review_cv_n179"
MAX_QUESTIONS = 60
REQUIRED_EXTRA_FIELDS = ["expected_questions", "reviewed_at", "gt_source"]


def clean_expected_questions(value: object) -> int:
    try:
        expected = int(str(value or "").strip())
    except ValueError:
        expected = MAX_QUESTIONS
    return max(1, min(MAX_QUESTIONS, expected))


def normalize_answer(value: object) -> str:
    text = str(value or "").strip().upper()
    if text in {"N", "NA", "N/A", "-"}:
        return "NA"
    cleaned = "".join(ch for ch in text if ch in "ABCDE+")
    while "++" in cleaned:
        cleaned = cleaned.replace("++", "+")
    return cleaned.strip("+")


def load_rows(csv_path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with csv_path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        fields = list(reader.fieldnames or [])
        rows = [dict(row) for row in reader]
    for field in REQUIRED_EXTRA_FIELDS:
        if field not in fields:
            fields.append(field)
            for row in rows:
                row.setdefault(field, "")
    for row in rows:
        row["expected_questions"] = str(clean_expected_questions(row.get("expected_questions")))
    return fields, rows


def write_rows_atomic(csv_path: Path, fields: list[str], rows: list[dict[str, str]]) -> None:
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=csv_path.name, suffix=".tmp", dir=str(csv_path.parent))
    os.close(fd)
    tmp_path = Path(tmp_name)
    try:
        with tmp_path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(rows)
        tmp_path.replace(csv_path)
    finally:
        if tmp_path.exists():
            tmp_path.unlink()


def row_summary(row: dict[str, str], index: int) -> dict[str, object]:
    auto_answers = [row.get(f"auto_q{i:02d}", "") for i in range(1, 61)]
    gt_answers = [row.get(f"gt_q{i:02d}", "") for i in range(1, 61)]
    expected = clean_expected_questions(row.get("expected_questions"))
    changed = sum(
        1 for a, b in zip(auto_answers[:expected], gt_answers[:expected])
        if (a or "") != (b or "")
    )
    return {
        "index": index,
        "sheet_id": row.get("sheet_id", ""),
        "dataset": row.get("dataset", ""),
        "image": row.get("image", ""),
        "expected_questions": str(expected),
        "preview_path": row.get("preview_path", ""),
        "verified": row.get("verified", ""),
        "needs_fix": row.get("needs_fix", ""),
        "review_priority": row.get("review_priority", ""),
        "auto_flags": row.get("auto_flags", ""),
        "notes": row.get("notes", ""),
        "gt_source": row.get("gt_source", ""),
        "reviewed_at": row.get("reviewed_at", ""),
        "exam_code_auto": row.get("exam_code_auto", ""),
        "mssv_auto": row.get("mssv_auto", ""),
        "answered_auto": row.get("answered_auto", ""),
        "suspicious_auto": row.get("suspicious_auto", ""),
        "multi_auto": row.get("multi_auto", ""),
        "status_auto": row.get("status_auto", ""),
        "cpp_ms": row.get("cpp_ms", ""),
        "auto_answers": auto_answers,
        "gt_answers": gt_answers,
        "changed_count": changed,
    }


def dataset_summary(rows: list[dict[str, str]]) -> list[dict[str, object]]:
    grouped: dict[str, dict[str, object]] = {}
    for row in rows:
        dataset = row.get("dataset", "") or "(blank)"
        entry = grouped.setdefault(
            dataset,
            {
                "dataset": dataset,
                "sheet_count": 0,
                "verified_count": 0,
                "needs_fix_count": 0,
                "expected_counts": {},
            },
        )
        entry["sheet_count"] = int(entry["sheet_count"]) + 1
        if row.get("verified") == "1":
            entry["verified_count"] = int(entry["verified_count"]) + 1
        if row.get("needs_fix") == "1":
            entry["needs_fix_count"] = int(entry["needs_fix_count"]) + 1
        expected = clean_expected_questions(row.get("expected_questions"))
        counts = entry["expected_counts"]
        assert isinstance(counts, dict)
        counts[str(expected)] = int(counts.get(str(expected), 0)) + 1

    summaries = []
    for dataset in sorted(grouped):
        entry = grouped[dataset]
        counts = entry["expected_counts"]
        assert isinstance(counts, dict)
        expected = max(counts.items(), key=lambda item: item[1])[0] if counts else str(MAX_QUESTIONS)
        summaries.append(
            {
                **entry,
                "expected_questions": expected,
                "expected_mixed": len(counts) > 1,
            }
        )
    return summaries


APP_HTML = r"""<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>OMR Ground Truth Review</title>
  <style>
    :root {
      --bg: #eef2f7;
      --panel: #ffffff;
      --ink: #0f172a;
      --muted: #64748b;
      --line: #cbd5e1;
      --green: #15803d;
      --green-bg: #dcfce7;
      --amber: #b45309;
      --amber-bg: #fef3c7;
      --red: #b91c1c;
      --red-bg: #fee2e2;
      --blue: #1d4ed8;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      height: 100vh;
      overflow: hidden;
      background: var(--bg);
      color: var(--ink);
      font: 14px/1.35 "Segoe UI", Arial, sans-serif;
    }
    header {
      height: 58px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 10px 16px;
      background: var(--panel);
      border-bottom: 1px solid var(--line);
    }
    h1 { margin: 0; font-size: 18px; }
    .top-meta { color: var(--muted); font-size: 12px; margin-top: 2px; }
    .toolbar, .nav, .filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .dataset-config {
      min-height: 82px;
      display: grid;
      grid-template-columns: 220px 1fr;
      gap: 12px;
      align-items: center;
      padding: 10px 16px;
      background: #f8fafc;
      border-bottom: 1px solid var(--line);
    }
    .dataset-title strong { display: block; font-size: 14px; }
    .dataset-title span { display: block; margin-top: 3px; color: var(--muted); font-size: 12px; }
    .dataset-list { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .dataset-item {
      display: inline-grid;
      grid-template-columns: auto 58px auto;
      align-items: center;
      gap: 6px;
      min-height: 34px;
      padding: 5px 6px;
      border: 1px solid var(--line);
      border-radius: 7px;
      background: #fff;
    }
    .dataset-item.mixed { border-color: var(--amber); background: #fffbeb; }
    .dataset-item label { font-size: 12px; font-weight: 700; white-space: nowrap; }
    .dataset-item input { width: 58px; min-height: 28px; padding: 4px 6px; }
    .dataset-item button { min-height: 28px; padding: 4px 8px; }
    button, select, input, textarea {
      font: inherit;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: #fff;
      color: var(--ink);
    }
    button {
      min-height: 34px;
      padding: 7px 11px;
      cursor: pointer;
      font-weight: 650;
    }
    button.primary { background: var(--green); border-color: var(--green); color: #fff; }
    button.blue { background: var(--blue); border-color: var(--blue); color: #fff; }
    button.warn { background: var(--amber); border-color: var(--amber); color: #fff; }
    button:disabled { opacity: 0.45; cursor: not-allowed; }
    select, input { min-height: 34px; padding: 6px 8px; }
    main {
      height: calc(100vh - 58px - 82px);
      display: grid;
      grid-template-columns: minmax(520px, 1.18fr) minmax(520px, 0.82fr);
      gap: 12px;
      padding: 12px;
    }
    .preview-pane, .edit-pane {
      min-width: 0;
      min-height: 0;
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      overflow: hidden;
    }
    .preview-pane {
      display: grid;
      grid-template-rows: auto 1fr;
    }
    .sheet-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 12px;
      border-bottom: 1px solid var(--line);
      background: #f8fafc;
    }
    .sheet-title { font-weight: 750; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .badges { display: flex; gap: 6px; flex-wrap: wrap; justify-content: flex-end; }
    .badge {
      display: inline-flex;
      align-items: center;
      min-height: 22px;
      border-radius: 999px;
      padding: 2px 8px;
      font-size: 12px;
      font-weight: 700;
      background: #e2e8f0;
      color: #334155;
      white-space: nowrap;
    }
    .badge.ok { background: var(--green-bg); color: #166534; }
    .badge.warn { background: var(--amber-bg); color: #92400e; }
    .badge.red { background: var(--red-bg); color: #991b1b; }
    .badge.blue { background: #dbeafe; color: #1e40af; }
    .image-wrap {
      min-height: 0;
      overflow: auto;
      background: #111827;
      display: grid;
      place-items: start center;
      padding: 12px;
    }
    .preview-stage {
      position: relative;
      display: inline-block;
      max-width: 100%;
      line-height: 0;
    }
    #previewImage {
      max-width: 100%;
      height: auto;
      display: block;
      background: #fff;
      box-shadow: 0 12px 26px rgba(0,0,0,0.24);
    }
    #invalidMaskLayer {
      position: absolute;
      inset: 0;
      pointer-events: none;
      overflow: hidden;
    }
    .invalid-mask {
      position: absolute;
      display: grid;
      place-items: center;
      padding: 2px;
      border: 2px solid rgba(220,38,38,0.92);
      background: rgba(248,113,113,0.24);
      color: #7f1d1d;
      font: 800 12px/1.2 "Segoe UI", Arial, sans-serif;
      text-align: center;
      text-shadow: 0 1px 0 rgba(255,255,255,0.78);
    }
    .invalid-mask.text-panel {
      place-items: start;
      align-items: center;
      justify-items: start;
      padding-left: 10px;
      border-color: rgba(185,28,28,0.82);
      background: rgba(255,255,255,0.9);
      color: #991b1b;
    }
    .edit-pane {
      display: grid;
      grid-template-rows: auto auto 1fr auto;
    }
    .info-grid {
      padding: 10px 12px;
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 8px;
      border-bottom: 1px solid var(--line);
      background: #f8fafc;
    }
    .info { min-width: 0; }
    .info span { display: block; font-size: 11px; color: var(--muted); text-transform: uppercase; }
    .info strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .note-row {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 8px;
      padding: 10px 12px;
      border-bottom: 1px solid var(--line);
    }
    textarea { width: 100%; min-height: 42px; padding: 8px; resize: vertical; }
    .question-grid {
      min-height: 0;
      overflow: auto;
      padding: 10px 12px;
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      align-content: start;
      gap: 8px;
    }
    .q-card {
      border: 1px solid var(--line);
      border-radius: 7px;
      padding: 7px;
      display: grid;
      grid-template-columns: 42px 1fr 58px;
      gap: 6px;
      align-items: center;
      background: #fff;
    }
    .q-card.changed { border-color: var(--amber); background: #fffbeb; }
    .q-card.blank { border-color: #94a3b8; }
    .q-card.out-of-range { border-color: #d1d5db; background: #f1f5f9; opacity: 0.74; }
    .qno { font-weight: 750; color: #334155; }
    .auto { font: 12px Consolas, monospace; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .q-card input { width: 58px; min-height: 30px; text-transform: uppercase; text-align: center; font: 13px Consolas, monospace; }
    .footer {
      padding: 10px 12px;
      border-top: 1px solid var(--line);
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      background: #f8fafc;
    }
    .status { color: var(--muted); font-size: 12px; }
    @media (max-width: 1100px) {
      body { overflow: auto; height: auto; }
      .dataset-config { grid-template-columns: 1fr; min-height: 0; }
      main { height: auto; grid-template-columns: 1fr; }
      .preview-pane, .edit-pane { min-height: 600px; }
      .question-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }
  </style>
</head>
<body>
<header>
  <div>
    <h1>OMR Ground Truth Review</h1>
    <div class="top-meta" id="topMeta">Loading...</div>
  </div>
  <div class="toolbar">
    <div class="filters">
      <select id="datasetFilter"></select>
      <select id="modeFilter">
        <option value="all">All sheets</option>
        <option value="todo">Unverified</option>
        <option value="flagged">Flagged first</option>
        <option value="dataset1">Dataset 1</option>
      </select>
    </div>
    <div class="nav">
      <button id="prevBtn">Prev</button>
      <input id="jumpInput" type="number" min="1" value="1" style="width:76px">
      <button id="jumpBtn">Go</button>
      <button id="nextBtn">Next</button>
    </div>
  </div>
</header>
<section class="dataset-config">
  <div class="dataset-title">
    <strong id="datasetCount">Datasets: -</strong>
    <span>Set valid question count; Apply writes NA to trailing GT cells.</span>
  </div>
  <div class="dataset-list" id="datasetList"></div>
</section>
<main>
  <section class="preview-pane">
    <div class="sheet-head">
      <div class="sheet-title" id="sheetTitle">-</div>
      <div class="badges" id="badges"></div>
    </div>
    <div class="image-wrap">
      <div class="preview-stage">
        <img id="previewImage" alt="preview">
        <div id="invalidMaskLayer"></div>
      </div>
    </div>
  </section>
  <section class="edit-pane">
    <div class="info-grid" id="infoGrid"></div>
    <div class="note-row">
      <textarea id="notes" placeholder="Notes for this sheet"></textarea>
      <button id="resetBtn">Reset GT to auto</button>
    </div>
    <div class="question-grid" id="questionGrid"></div>
    <div class="footer">
      <div class="status" id="statusText">-</div>
      <div class="toolbar">
        <button id="saveBtn">Save</button>
        <button id="acceptBtn" class="primary">Accept</button>
        <button id="saveAcceptNextBtn" class="blue">Save & Accept, Next</button>
        <button id="markFixBtn" class="warn">Save as Needs Fix</button>
      </div>
    </div>
  </section>
</main>
<script>
const state = { rows: [], fields: [], datasets: [], filtered: [], pos: 0, dirty: false };
const $ = (id) => document.getElementById(id);
const PREVIEW_GEOMETRY = {
  sourceWidth: 1700,
  sourceHeight: 2400,
  targetHeight: 1200,
  panelWidth: 520,
  answerTextY: 558,
  answerTextLineHeight: 40,
  blocks: [
    { start: 1, end: 10, x1: 880, y1: 860, x2: 1183, y2: 1530 },
    { start: 11, end: 20, x1: 1249, y1: 856, x2: 1563, y2: 1527 },
    { start: 21, end: 30, x1: 140, y1: 1604, x2: 447, y2: 2311 },
    { start: 31, end: 40, x1: 515, y1: 1597, x2: 819, y2: 2303 },
    { start: 41, end: 50, x1: 882, y1: 1593, x2: 1194, y2: 2311 },
    { start: 51, end: 60, x1: 1251, y1: 1585, x2: 1568, y2: 2292 }
  ]
};

function generatedPreviewSize() {
  const scale = PREVIEW_GEOMETRY.targetHeight / PREVIEW_GEOMETRY.sourceHeight;
  const imageWidth = PREVIEW_GEOMETRY.sourceWidth * scale;
  return {
    scale,
    width: imageWidth + PREVIEW_GEOMETRY.panelWidth,
    height: PREVIEW_GEOMETRY.targetHeight,
    imageWidth
  };
}

function pct(value, total) {
  return `${(value * 100 / total).toFixed(4)}%`;
}

function normAnswer(value) {
  const raw = String(value || "").trim().toUpperCase();
  if (["N", "NA", "N/A", "-"].includes(raw)) return "NA";
  return raw
    .replace(/[^A-E+]/g, "")
    .replace(/\++/g, "+")
    .replace(/^\+|\+$/g, "");
}

function expectedFor(row) {
  const n = Number(row.expected_questions || 60);
  if (!Number.isFinite(n)) return 60;
  return Math.max(1, Math.min(60, Math.round(n)));
}

function changedCount(row) {
  let n = 0;
  const expected = expectedFor(row);
  for (let i = 0; i < expected; i++) {
    if ((row.auto_answers[i] || "") !== (row.gt_answers[i] || "")) n += 1;
  }
  return n;
}

async function api(path, options = {}) {
  const res = await fetch(path, options);
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}

async function loadState() {
  const data = await api("/api/state");
  state.rows = data.rows;
  state.fields = data.fields;
  state.datasets = data.datasets || [];
  setupFilters();
  renderDatasetConfig();
  applyFilters();
  renderCurrent();
}

function setupFilters(selectedDataset = "") {
  const datasets = ["all", ...Array.from(new Set(state.rows.map(r => r.dataset))).sort()];
  $("datasetFilter").innerHTML = datasets.map(d => `<option value="${escapeHtml(d)}">${d === "all" ? "All datasets" : escapeHtml(d)}</option>`).join("");
  if (selectedDataset && datasets.includes(selectedDataset)) $("datasetFilter").value = selectedDataset;
}

function renderDatasetConfig() {
  $("datasetCount").textContent = `Datasets: ${state.datasets.length} | sheets: ${state.rows.length}`;
  $("datasetList").innerHTML = state.datasets.map((item, idx) => {
    const mixed = item.expected_mixed ? " mixed" : "";
    const title = item.expected_mixed ? ` title="Mixed expected_questions values: ${escapeHtml(JSON.stringify(item.expected_counts))}"` : "";
    return `<div class="dataset-item${mixed}"${title}>
      <label>${escapeHtml(item.dataset)} (${item.sheet_count})</label>
      <input id="expected-${idx}" type="number" min="1" max="60" value="${escapeHtml(String(item.expected_questions || 60))}">
      <button type="button" class="dataset-apply" data-index="${idx}">Apply</button>
    </div>`;
  }).join("");
  document.querySelectorAll(".dataset-apply").forEach((button) => {
    button.addEventListener("click", () => saveDatasetConfig(Number(button.dataset.index)));
  });
}

async function saveDatasetConfig(index) {
  const item = state.datasets[index];
  if (!item) return;
  const input = $(`expected-${index}`);
  let expected = Math.round(Number(input.value || 60));
  if (!Number.isFinite(expected)) expected = 60;
  expected = Math.max(1, Math.min(60, expected));
  input.value = String(expected);
  try {
    updateStatus(`Applying ${item.dataset}: ${expected}/60...`);
    const data = await api("/api/save_dataset_config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ dataset: item.dataset, expected_questions: expected, apply_na: true })
    });
    state.rows = data.rows;
    state.datasets = data.datasets || [];
    renderDatasetConfig();
    setupFilters(item.dataset);
    $("datasetFilter").value = item.dataset;
    if ($("modeFilter").value === "dataset1" && item.dataset !== "dataset_1") {
      $("modeFilter").value = "all";
    }
    state.pos = 0;
    applyFilters();
    updateStatus(`Applied ${item.dataset}: ${expected}/60; trailing GT cells are NA`);
  } catch (error) {
    updateStatus(`Apply failed: ${error.message}`);
  }
}

function applyFilters() {
  const dataset = $("datasetFilter").value || "all";
  const mode = $("modeFilter").value || "all";
  state.filtered = state.rows.filter((row) => {
    if (dataset !== "all" && row.dataset !== dataset) return false;
    if (mode === "todo" && row.verified === "1") return false;
    if (mode === "flagged" && !row.auto_flags) return false;
    if (mode === "dataset1" && row.dataset !== "dataset_1") return false;
    return true;
  });
  if (!state.filtered.length) {
    state.pos = 0;
  } else if (state.pos >= state.filtered.length) {
    state.pos = state.filtered.length - 1;
  }
  renderCurrent();
}

function currentRow() {
  return state.filtered[state.pos] || null;
}

function renderInvalidPreviewMask(row) {
  const layer = $("invalidMaskLayer");
  if (!layer) return;
  if (!row) {
    layer.innerHTML = "";
    return;
  }
  const expected = expectedFor(row);
  if (expected >= 60) {
    layer.innerHTML = "";
    return;
  }

  const size = generatedPreviewSize();
  const masks = [];
  for (const block of PREVIEW_GEOMETRY.blocks) {
    const invalidStart = Math.max(expected + 1, block.start);
    const invalidEnd = block.end;
    if (invalidStart > invalidEnd) continue;
    const rowStart = invalidStart - block.start;
    const rowCount = invalidEnd - invalidStart + 1;
    const cellH = (block.y2 - block.y1) / 10;
    const x = block.x1 * size.scale;
    const y = (block.y1 + rowStart * cellH) * size.scale;
    const w = (block.x2 - block.x1) * size.scale;
    const h = rowCount * cellH * size.scale;
    masks.push(`<div class="invalid-mask" style="left:${pct(x, size.width)};top:${pct(y, size.height)};width:${pct(w, size.width)};height:${pct(h, size.height)};">Q${invalidStart}-Q${invalidEnd}<br>NA</div>`);
  }

  const groups = [1, 11, 21, 31, 41, 51];
  groups.forEach((start, idx) => {
    const end = start + 9;
    if (expected >= end) return;
    const invalidStart = Math.max(expected + 1, start);
    const x = size.imageWidth + 18;
    const y = PREVIEW_GEOMETRY.answerTextY + idx * PREVIEW_GEOMETRY.answerTextLineHeight - 13;
    masks.push(`<div class="invalid-mask text-panel" style="left:${pct(x, size.width)};top:${pct(y, size.height)};width:${pct(478, size.width)};height:${pct(31, size.height)};">Q${invalidStart}-Q${end} excluded from GT</div>`);
  });

  layer.innerHTML = masks.join("");
}

function badge(text, cls = "") {
  return `<span class="badge ${cls}">${text}</span>`;
}

function renderCurrent() {
  const total = state.rows.length;
  const verified = state.rows.filter(r => r.verified === "1").length;
  $("topMeta").textContent = `${verified}/${total} verified | filtered ${state.filtered.length} | datasets ${state.datasets.length}`;

  const row = currentRow();
  if (!row) {
    $("sheetTitle").textContent = "No sheets match current filter";
    $("previewImage").removeAttribute("src");
    renderInvalidPreviewMask(null);
    $("questionGrid").innerHTML = "";
    $("infoGrid").innerHTML = "";
    $("badges").innerHTML = "";
    $("statusText").textContent = "-";
    return;
  }

  state.dirty = false;
  $("jumpInput").value = String(state.pos + 1);
  $("jumpInput").max = String(state.filtered.length);
  $("sheetTitle").textContent = `${state.pos + 1}/${state.filtered.length} - ${row.sheet_id}`;
  $("previewImage").src = "/" + row.preview_path.replace(/\\/g, "/");
  $("notes").value = row.notes || "";
  const expected = expectedFor(row);
  renderInvalidPreviewMask(row);

  const badges = [];
  badges.push(row.verified === "1" ? badge("verified", "ok") : badge("unverified", "warn"));
  badges.push(badge(`expected ${expected}/60`, "blue"));
  if (row.needs_fix === "1") badges.push(badge("needs fix", "red"));
  if (row.dataset === "dataset_1") badges.push(badge("dataset_1 labelled", "blue"));
  if (row.auto_flags) badges.push(badge(row.auto_flags, "warn"));
  const changed = changedCount(row);
  if (changed) badges.push(badge(`${changed} edited`, "warn"));
  $("badges").innerHTML = badges.join("");

  const infos = [
    ["Dataset", row.dataset],
    ["Image", row.image],
    ["Exam", row.exam_code_auto || "-"],
    ["MSSV", row.mssv_auto || "-"],
    ["Answered", `${row.answered_auto}/60`],
    ["Expected", `${expected}/60`],
    ["Suspicious", row.suspicious_auto || "0"],
    ["Multi", row.multi_auto || "0"],
    ["Source", row.gt_source || "prelabel"]
  ];
  $("infoGrid").innerHTML = infos.map(([k, v]) => `<div class="info"><span>${k}</span><strong>${escapeHtml(String(v))}</strong></div>`).join("");

  $("questionGrid").innerHTML = row.gt_answers.map((gt, idx) => {
    const auto = row.auto_answers[idx] || "";
    const q = idx + 1;
    const outOfRange = idx >= expected;
    const value = outOfRange ? "NA" : (gt || "");
    const cls = outOfRange ? "out-of-range" : ((value || "") !== auto ? "changed" : (!value ? "blank" : ""));
    return `<label class="q-card ${cls}" data-q="${idx}">
      <span class="qno">Q${String(q).padStart(2, "0")}</span>
      <span class="auto">auto: ${escapeHtml(auto || "-")}</span>
      <input data-q="${idx}" value="${escapeHtml(value)}" placeholder="-" ${outOfRange ? "disabled" : ""}>
    </label>`;
  }).join("");
  document.querySelectorAll("#questionGrid input").forEach((input) => {
    if (input.disabled) return;
    input.addEventListener("input", () => {
      const idx = Number(input.dataset.q);
      input.value = normAnswer(input.value);
      row.gt_answers[idx] = input.value;
      const card = input.closest(".q-card");
      const auto = row.auto_answers[idx] || "";
      card.classList.toggle("changed", input.value !== auto);
      card.classList.toggle("blank", !input.value);
      state.dirty = true;
      updateStatus();
    });
  });
  $("notes").oninput = () => { row.notes = $("notes").value; state.dirty = true; updateStatus(); };

  $("prevBtn").disabled = state.pos <= 0;
  $("nextBtn").disabled = state.pos >= state.filtered.length - 1;
  updateStatus();
}

function updateStatus(text) {
  if (text) {
    $("statusText").textContent = text;
    return;
  }
  const row = currentRow();
  if (!row) return;
  const changed = changedCount(row);
  $("statusText").textContent = `${state.dirty ? "Unsaved changes" : "Saved state loaded"} | ${changed} GT cells differ from auto`;
}

async function saveCurrent({ accept = false, needsFix = false, next = false } = {}) {
  const row = currentRow();
  if (!row) return;
  const payload = {
    index: row.index,
    expected_questions: String(expectedFor(row)),
    gt_answers: row.gt_answers.map((value, idx) => idx >= expectedFor(row) ? "NA" : normAnswer(value)),
    notes: row.notes || "",
    verified: accept ? "1" : row.verified,
    needs_fix: needsFix ? "1" : (changedCount(row) ? "1" : row.needs_fix),
    gt_source: accept ? (row.dataset === "dataset_1" ? "human_verified_existing_dataset1_label" : "human_verified") : (row.gt_source || "prelabel")
  };
  updateStatus("Saving...");
  const saved = await api("/api/save", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  const globalIndex = state.rows.findIndex(r => r.index === row.index);
  if (globalIndex >= 0) state.rows[globalIndex] = saved.row;
  const filteredIndex = state.filtered.findIndex(r => r.index === row.index);
  if (filteredIndex >= 0) state.filtered[filteredIndex] = saved.row;
  state.dirty = false;
  updateStatus("Saved");
  if (next) {
    if (state.pos < state.filtered.length - 1) state.pos += 1;
    renderCurrent();
  } else {
    renderCurrent();
  }
}

function escapeHtml(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
}

$("datasetFilter").addEventListener("change", () => { state.pos = 0; applyFilters(); });
$("modeFilter").addEventListener("change", () => { state.pos = 0; applyFilters(); });
$("prevBtn").addEventListener("click", () => { if (state.pos > 0) { state.pos -= 1; renderCurrent(); } });
$("nextBtn").addEventListener("click", () => { if (state.pos < state.filtered.length - 1) { state.pos += 1; renderCurrent(); } });
$("jumpBtn").addEventListener("click", () => {
  const n = Math.max(1, Math.min(state.filtered.length, Number($("jumpInput").value || 1)));
  state.pos = n - 1;
  renderCurrent();
});
$("resetBtn").addEventListener("click", () => {
  const row = currentRow();
  if (!row) return;
  const expected = expectedFor(row);
  row.gt_answers = row.auto_answers.map((value, idx) => idx >= expected ? "NA" : value);
  renderCurrent();
  state.dirty = true;
  updateStatus();
});
$("saveBtn").addEventListener("click", () => saveCurrent());
$("acceptBtn").addEventListener("click", () => saveCurrent({ accept: true }));
$("saveAcceptNextBtn").addEventListener("click", () => saveCurrent({ accept: true, next: true }));
$("markFixBtn").addEventListener("click", () => saveCurrent({ accept: true, needsFix: true }));
document.addEventListener("keydown", (event) => {
  if (event.ctrlKey && event.key === "Enter") {
    event.preventDefault();
    saveCurrent({ accept: true, next: true });
  }
});

loadState().catch((error) => {
  $("topMeta").textContent = "Failed to load";
  $("statusText").textContent = error.message;
});
</script>
</body>
</html>
"""


class ReviewHandler(BaseHTTPRequestHandler):
    review_dir: Path
    csv_path: Path

    def log_message(self, fmt, *args):
        if args and str(args[1]) == "200":
            return
        super().log_message(fmt, *args)

    def send_json(self, payload: object, status: int = 200) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_text(self, text: str, status: int = 200, content_type: str = "text/plain; charset=utf-8") -> None:
        data = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        if path in ("/", "/review", "/review.html"):
            self.send_text(APP_HTML, content_type="text/html; charset=utf-8")
            return
        if path == "/api/state":
            fields, rows = load_rows(self.csv_path)
            self.send_json({
                "fields": fields,
                "rows": [row_summary(row, idx) for idx, row in enumerate(rows)],
                "datasets": dataset_summary(rows),
                "csv_path": str(self.csv_path),
            })
            return
        if path.startswith("/previews/"):
            rel = path.lstrip("/")
            target = (self.review_dir / rel).resolve()
            try:
                target.relative_to(self.review_dir.resolve())
            except ValueError:
                self.send_error(403)
                return
            if not target.exists() or not target.is_file():
                self.send_error(404)
                return
            ctype = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
            data = target.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_error(404)

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path not in ("/api/save", "/api/save_dataset_config"):
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length).decode("utf-8"))
        fields, rows = load_rows(self.csv_path)

        if parsed.path == "/api/save_dataset_config":
            dataset = str(payload.get("dataset", "")).strip()
            expected = clean_expected_questions(payload.get("expected_questions"))
            apply_na = bool(payload.get("apply_na", True))
            matched = 0
            for row in rows:
                if (row.get("dataset", "") or "(blank)") != dataset:
                    continue
                matched += 1
                row["expected_questions"] = str(expected)
                if apply_na:
                    for i in range(1, MAX_QUESTIONS + 1):
                        key = f"gt_q{i:02d}"
                        if i > expected:
                            row[key] = "NA"
                        elif normalize_answer(row.get(key)) == "NA":
                            row[key] = normalize_answer(row.get(f"auto_q{i:02d}"))
            if not matched:
                self.send_json({"error": f"dataset not found: {dataset}"}, status=400)
                return
            write_rows_atomic(self.csv_path, fields, rows)
            self.send_json({
                "ok": True,
                "updated": matched,
                "rows": [row_summary(row, idx) for idx, row in enumerate(rows)],
                "datasets": dataset_summary(rows),
            })
            return

        index = int(payload["index"])
        if index < 0 or index >= len(rows):
            self.send_json({"error": "row index out of range"}, status=400)
            return
        row = rows[index]
        expected = clean_expected_questions(payload.get("expected_questions", row.get("expected_questions")))
        row["expected_questions"] = str(expected)
        gt_answers = payload.get("gt_answers") or []
        if len(gt_answers) != 60:
            self.send_json({"error": "gt_answers must have length 60"}, status=400)
            return
        for i, value in enumerate(gt_answers, start=1):
            row[f"gt_q{i:02d}"] = "NA" if i > expected else normalize_answer(value)
        row["verified"] = str(payload.get("verified", row.get("verified", "")) or "")
        row["needs_fix"] = str(payload.get("needs_fix", row.get("needs_fix", "")) or "")
        row["notes"] = str(payload.get("notes", row.get("notes", "")) or "")
        row["gt_source"] = str(payload.get("gt_source", row.get("gt_source", "")) or "")
        row["reviewed_at"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        rows[index] = row
        write_rows_atomic(self.csv_path, fields, rows)
        self.send_json({"ok": True, "row": row_summary(row, index)})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--review-dir", default=str(DEFAULT_REVIEW_DIR))
    parser.add_argument("--port", type=int, default=8092)
    args = parser.parse_args()

    review_dir = Path(args.review_dir).resolve()
    csv_path = review_dir / "ground_truth_review_template.csv"
    if not csv_path.exists():
        raise SystemExit(f"CSV not found: {csv_path}")

    ReviewHandler.review_dir = review_dir
    ReviewHandler.csv_path = csv_path
    server = ThreadingHTTPServer(("127.0.0.1", args.port), ReviewHandler)
    print(f"Review UI: http://127.0.0.1:{args.port}/review")
    print(f"CSV: {csv_path}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
