#!/usr/bin/env python3
"""Generate human-review OMR pre-label previews.

This script runs the browser/WASM OMR pipeline, writes one annotated preview
image per sheet, and creates a CSV that can become ground truth only after a
human verifies or corrects the pre-filled `gt_qXX` columns.

Default output:
  runs/accuracy_eval/review_cv_n179/

Usage:
  python scripts/generate_review_previews.py
  python scripts/generate_review_previews.py --method yolo --limit 10
"""
from __future__ import annotations

import argparse
import base64
import csv
import html
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path

import websocket  # type: ignore


REPO = Path(__file__).resolve().parent.parent
SERVER_PORT = 8091
CDP_PORT = 9333

CHROME_PATHS = {
    "Windows": [
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    ],
    "Darwin": [
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
    ],
    "Linux": [
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium-browser",
        "/usr/bin/chromium",
    ],
}


def find_chrome() -> str:
    exe = os.environ.get("CHROME")
    if exe and Path(exe).exists():
        return exe
    for candidate in CHROME_PATHS.get(platform.system(), []):
        if Path(candidate).exists():
            return candidate
    found = shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chrome")
    if found:
        return found
    raise RuntimeError("Chrome not found. Set CHROME=/path/to/chrome.")


def wait_http(url: str, timeout_s: int = 30) -> None:
    deadline = time.time() + timeout_s
    last_error: Exception | None = None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                response.read(32)
            return
        except Exception as exc:  # pragma: no cover - diagnostic path
            last_error = exc
            time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for {url}: {last_error}")


def read_json_url(url: str):
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.loads(response.read())


class CDP:
    def __init__(self, ws_url: str):
        self.ws = websocket.create_connection(ws_url, suppress_origin=True, timeout=180)
        self.seq = 0

    def call(self, method: str, params=None):
        self.seq += 1
        msg = {"id": self.seq, "method": method}
        if params is not None:
            msg["params"] = params
        self.ws.send(json.dumps(msg))
        while True:
            data = json.loads(self.ws.recv())
            if data.get("id") == self.seq:
                if "error" in data:
                    raise RuntimeError(f"{method}: {data['error']}")
                return data.get("result")

    def js(self, expr: str, await_promise: bool = False):
        result = self.call(
            "Runtime.evaluate",
            {
                "expression": expr,
                "awaitPromise": await_promise,
                "returnByValue": True,
            },
        ).get("result", {})
        if result.get("subtype") == "error":
            raise RuntimeError(result.get("description") or "JavaScript error")
        return result.get("value")

    def close(self) -> None:
        try:
            self.ws.close()
        except Exception:
            pass


def start_server(port: int) -> subprocess.Popen:
    logs = REPO / "logs"
    logs.mkdir(exist_ok=True)
    stdout = open(logs / "review_server.log", "w", encoding="utf-8")
    stderr = open(logs / "review_server_err.log", "w", encoding="utf-8")
    return subprocess.Popen(
        [sys.executable, "web/server.py", str(port), "--dataset=Dataset_OMR_classified"],
        cwd=REPO,
        stdout=stdout,
        stderr=stderr,
    )


def start_chrome(chrome: str, cdp_port: int, url: str) -> subprocess.Popen:
    user_data_dir = Path(tempfile.gettempdir()) / "omr_review_cdp"
    user_data_dir.mkdir(exist_ok=True)
    args = [
        chrome,
        "--headless=new",
        f"--remote-debugging-port={cdp_port}",
        f"--user-data-dir={user_data_dir}",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-background-timer-throttling",
        "--disable-renderer-backgrounding",
        "--disable-backgrounding-occluded-windows",
        "--autoplay-policy=no-user-gesture-required",
        url,
    ]
    return subprocess.Popen(args, cwd=REPO, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def sanitize_filename(text: str, max_len: int = 120) -> str:
    text = urllib.parse.unquote(text)
    text = text.replace("\\", "_").replace("/", "_")
    text = re.sub(r"[^A-Za-z0-9._-]+", "_", text)
    text = text.strip("._")
    if len(text) > max_len:
        stem, suffix = os.path.splitext(text)
        text = stem[: max_len - len(suffix) - 1] + suffix
    return text or "sheet"


def preview_name(index: int, url: str) -> str:
    decoded = urllib.parse.unquote(url)
    parts = decoded.strip("/").split("/")
    dataset = parts[1] if len(parts) >= 2 and parts[0] == "dataset" else "dataset"
    image = parts[-1] if parts else f"sheet_{index:03d}.jpg"
    image_stem = Path(image).stem
    return f"{index:03d}_{sanitize_filename(dataset)}_{sanitize_filename(image_stem)}.jpg"


def decode_data_url(data_url: str) -> bytes:
    prefix = "base64,"
    idx = data_url.find(prefix)
    if idx < 0:
        raise ValueError("Expected base64 data URL")
    return base64.b64decode(data_url[idx + len(prefix) :])


def csv_fields() -> list[str]:
    base = [
        "verified",
        "needs_fix",
        "review_priority",
        "auto_flags",
        "expected_questions",
        "sheet_id",
        "dataset",
        "image",
        "source_url",
        "preview_path",
        "exam_code_auto",
        "mssv_auto",
        "mssv_valid_auto",
        "key_valid_auto",
        "answered_auto",
        "suspicious_auto",
        "multi_auto",
        "status_auto",
        "cpp_ms",
        "worker_total_ms",
        "yolo_ms",
        "yolo_detected",
        "yolo_pad_pct",
        "yolo_mask_mode",
        "yolo_fallback",
        "yolo_chosen_path",
        "yolo_fallback_used",
        "yolo_chosen_score",
        "yolo_fallback_score",
        "notes",
    ]
    auto = [f"auto_q{i:02d}" for i in range(1, 61)]
    gt = [f"gt_q{i:02d}" for i in range(1, 61)]
    return base + auto + gt


def write_index(out_dir: Path, rows: list[dict[str, str]], method: str) -> None:
    cards = []
    for row in rows:
        status = "warn" if row.get("auto_flags") else "ok"
        answers = " ".join(
            f"{i:02d}:{row.get(f'auto_q{i:02d}', '') or '-'}" for i in range(1, 61)
        )
        cards.append(
            f"""
            <article class="card {status}">
              <a href="{html.escape(row['preview_path'])}" target="_blank">
                <img src="{html.escape(row['preview_path'])}" alt="{html.escape(row['sheet_id'])}" loading="lazy">
              </a>
              <div class="meta">
                <h2>{html.escape(row['sheet_id'])}</h2>
                <p>exam={html.escape(row['exam_code_auto']) or '-'} |
                   mssv={html.escape(row['mssv_auto']) or '-'} |
                   answered={html.escape(row['answered_auto'])}/60 |
                   suspicious={html.escape(row['suspicious_auto'])} |
                   multi={html.escape(row['multi_auto'])}</p>
                <pre>{html.escape(answers)}</pre>
              </div>
            </article>
            """
        )

    html_doc = f"""<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <title>OMR Review Pre-label ({html.escape(method)})</title>
  <style>
    body {{ margin: 0; padding: 18px; font-family: Arial, sans-serif; background: #f8fafc; color: #0f172a; }}
    header {{ position: sticky; top: 0; background: rgba(248,250,252,0.96); padding: 12px 0; border-bottom: 1px solid #cbd5e1; }}
    h1 {{ margin: 0 0 6px; font-size: 22px; }}
    p {{ margin: 4px 0; color: #475569; }}
    .card {{ display: grid; grid-template-columns: 320px 1fr; gap: 14px; margin: 14px 0; padding: 12px; background: white; border: 1px solid #cbd5e1; border-radius: 8px; }}
    .card.warn {{ border-color: #f59e0b; }}
    img {{ width: 320px; height: auto; border: 1px solid #cbd5e1; }}
    h2 {{ margin: 0 0 8px; font-size: 16px; }}
    pre {{ white-space: pre-wrap; font: 12px/1.45 Consolas, monospace; background: #f1f5f9; padding: 8px; border-radius: 6px; }}
  </style>
</head>
<body>
  <header>
    <h1>OMR review pre-label ({html.escape(method)}, N={len(rows)})</h1>
    <p>Green circles are detected answer bubbles. Yellow marks suspicious bubbles. This is not ground truth until verified in the CSV.</p>
    <p>Edit <code>ground_truth_review_template.csv</code>: set <code>verified=1</code> for checked sheets and correct <code>gt_q01..gt_q60</code> where needed.</p>
  </header>
  {''.join(cards)}
</body>
</html>
"""
    (out_dir / "index.html").write_text(html_doc, encoding="utf-8")


def write_readme(out_dir: Path, method: str, n: int, config: dict[str, str] | None = None) -> None:
    config = config or {}
    config_lines = ""
    if config:
        config_lines = "\nYOLO preprocessing config:\n\n" + "\n".join(
            f"- `{key}`: `{value}`" for key, value in config.items()
        ) + "\n"
    text = f"""# OMR review pre-label

Method used for pre-labeling: `{method}`

Sheets processed: {n}
{config_lines}

This directory is for human verification. It is not a ground-truth artifact
until the CSV rows have been checked and corrected by a human.

Files:

- `index.html`: visual review page.
- `previews/*.jpg`: one annotated preview per sheet.
- `ground_truth_review_template.csv`: editable review CSV.
- `review_rows.jsonl`: same rows as JSON Lines for scripts.

How to verify:

1. Open `index.html`.
2. For each sheet, inspect the green selected bubbles and the answer text.
3. In `ground_truth_review_template.csv`, set `verified=1` when the pre-filled
   `gt_q01..gt_q60` answers are correct.
4. If a sheet is wrong, edit the affected `gt_qXX` cells and set `needs_fix=1`
   or add a note.
5. Leave `auto_qXX` unchanged; those are the model/pipeline predictions.

Columns:

- `verified`: user-owned. Put `1` after checking the sheet.
- `needs_fix`: user-owned. Put `1` if you changed any `gt_qXX` cell.
- `expected_questions`: valid question count for this dataset. Questions beyond
  this number should use `NA` in `gt_qXX` and are excluded from accuracy
  denominators.
- `review_priority` / `auto_flags`: generated hints for which sheets deserve
  extra attention first. They are not correctness labels.

Answer cell convention:

- blank = unanswered
- `A`, `B`, `C`, `D`, `E` = one selected option
- `A+B` style = multi-mark

Next step after verification:

- Save the reviewed CSV as the ground-truth source for the accuracy evaluator.
"""
    (out_dir / "README_REVIEW.md").write_text(text, encoding="utf-8")


def terminate(proc: subprocess.Popen | None) -> None:
    if not proc:
        return
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--method", choices=["cv", "yolo"], default="cv")
    parser.add_argument("--limit", type=int, default=0, help="debug limit; 0 means all manifest entries")
    parser.add_argument("--server-port", type=int, default=SERVER_PORT)
    parser.add_argument("--cdp-port", type=int, default=CDP_PORT)
    parser.add_argument("--out-dir", default=None)
    parser.add_argument("--yolo-pad", type=float, default=0.12, help="YOLO bbox expansion fraction")
    parser.add_argument("--yolo-mask", choices=["mask", "raw", "hint"], default="mask", help="YOLO preprocessing mode")
    parser.add_argument("--yolo-fallback", choices=["none", "bestdiag"], default="none", help="Optional diagnostic fallback")
    args = parser.parse_args()

    manifest = json.loads((REPO / "web" / "manifest.json").read_text(encoding="utf-8"))
    if args.limit:
        manifest = manifest[: args.limit]

    out_dir = Path(args.out_dir) if args.out_dir else REPO / "runs" / "accuracy_eval" / f"review_{args.method}_n{len(manifest)}"
    if not out_dir.is_absolute():
        out_dir = REPO / out_dir
    previews_dir = out_dir / "previews"
    previews_dir.mkdir(parents=True, exist_ok=True)

    server = None
    chrome_proc = None
    cdp = None
    try:
        server = start_server(args.server_port)
        wait_http(f"http://localhost:{args.server_port}/manifest.json", timeout_s=30)

        chrome = find_chrome()
        page_params = {"method": args.method}
        yolo_config: dict[str, str] = {}
        if args.method == "yolo":
            yolo_config = {
                "pad": str(args.yolo_pad),
                "mask": args.yolo_mask,
                "fallback": args.yolo_fallback,
            }
            page_params.update(yolo_config)
        url = f"http://localhost:{args.server_port}/review-prelabel.html?{urllib.parse.urlencode(page_params)}"
        chrome_proc = start_chrome(chrome, args.cdp_port, url)
        wait_http(f"http://localhost:{args.cdp_port}/json", timeout_s=30)

        pages = [p for p in read_json_url(f"http://localhost:{args.cdp_port}/json") if p.get("type") == "page"]
        if not pages:
            raise RuntimeError("No Chrome page target found")
        page = pages[0]
        cdp = CDP(page["webSocketDebuggerUrl"])
        cdp.call("Page.enable")

        deadline = time.time() + 90
        while time.time() < deadline:
            err = cdp.js("window.__reviewError || ''")
            if err:
                raise RuntimeError(f"review page error: {err}")
            if cdp.js("window.__reviewReady === true"):
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Timed out waiting for review page readiness")

        rows: list[dict[str, str]] = []
        jsonl_path = out_dir / "review_rows.jsonl"
        with jsonl_path.open("w", encoding="utf-8") as jsonl:
            for idx, url_path in enumerate(manifest, start=1):
                name = preview_name(idx, url_path)
                preview_rel = f"previews/{name}"
                expression = (
                    "window.__processReviewUrl("
                    + json.dumps(url_path)
                    + ", "
                    + json.dumps(preview_rel)
                    + ").then(JSON.stringify)"
                )
                value = cdp.js(expression, await_promise=True)
                payload = json.loads(value)
                image_bytes = decode_data_url(payload["previewDataUrl"])
                (previews_dir / name).write_bytes(image_bytes)
                row = {field: payload["row"].get(field, "") for field in csv_fields()}

                # Prioritize manual attention while still letting clean sheets be accepted quickly.
                try:
                    answered = int(row.get("answered_auto") or 0)
                    suspicious = int(row.get("suspicious_auto") or 0)
                    multi = int(row.get("multi_auto") or 0)
                    status = int(row.get("status_auto") or 0)
                except ValueError:
                    answered, suspicious, multi, status = 0, 999, 999, -1
                flags = []
                if status != 0:
                    flags.append(f"status={status}")
                if answered < 60:
                    flags.append(f"answered={answered}")
                if suspicious > 0:
                    flags.append(f"suspicious={suspicious}")
                if multi > 0:
                    flags.append(f"multi={multi}")
                row["auto_flags"] = ";".join(flags)
                row["review_priority"] = "high" if status != 0 or answered < 55 or multi > 0 else ("medium" if flags else "low")

                rows.append(row)
                jsonl.write(json.dumps(row, ensure_ascii=False) + "\n")
                if idx == 1 or idx % 10 == 0 or idx == len(manifest):
                    print(f"[{idx}/{len(manifest)}] {row['sheet_id']} answered={row['answered_auto']} susp={row['suspicious_auto']} multi={row['multi_auto']}", flush=True)

        csv_path = out_dir / "ground_truth_review_template.csv"
        with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=csv_fields())
            writer.writeheader()
            writer.writerows(rows)

        write_index(out_dir, rows, args.method)
        write_readme(out_dir, args.method, len(rows), yolo_config)

        print(f"[OK] Wrote {len(rows)} review rows")
        print(f"[OK] {csv_path}")
        print(f"[OK] {out_dir / 'index.html'}")
        print(f"[OK] {previews_dir}")
        return 0
    finally:
        if cdp:
            cdp.close()
        terminate(chrome_proc)
        terminate(server)


if __name__ == "__main__":
    raise SystemExit(main())
