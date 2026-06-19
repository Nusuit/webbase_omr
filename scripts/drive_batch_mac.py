"""Drive Chrome on macOS via CDP to run batch-detect.html on n=179 dataset.

Same logic as drive_batch.py; adds 'mac' as a platform choice so output files
land in runs/batch_results/mac_<method>_n179.json and
runs/resources/mac_air_m1/resources_mac_<method>_n179.csv without touching pc/mobile results.

Usage:
  python scripts/drive_batch_mac.py <platform> <method> [--timeout 2400]

  platform: mac
  method:   cv | yolo
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.request

import websocket  # type: ignore


def cdp_pages(cdp_host: str):
    with urllib.request.urlopen(f"{cdp_host}/json") as r:
        return json.loads(r.read())


class WS:
    def __init__(self, ws_url: str):
        self.ws = websocket.create_connection(ws_url, suppress_origin=True, timeout=120)
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
        r = self.call(
            "Runtime.evaluate",
            {"expression": expr, "awaitPromise": await_promise, "returnByValue": True},
        ).get("result", {})
        if r.get("subtype") == "error":
            raise RuntimeError(f"JS: {r.get('description')}")
        return r.get("value")

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def pick_target(cdp_host: str, url_hint: str = ""):
    pages = [p for p in cdp_pages(cdp_host) if p.get("type") == "page"]
    if not pages:
        raise RuntimeError("No Chrome tabs available. Open Chrome first.")
    if url_hint:
        for p in pages:
            if url_hint in (p.get("url") or ""):
                return p
    for p in pages:
        u = p.get("url") or ""
        if not u.startswith("chrome-extension://") and not u.startswith("chrome://"):
            return p
    return pages[0]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("platform", choices=["mac", "iphone"])
    ap.add_argument("method", choices=["cv", "yolo", "yolocorner"])
    ap.add_argument("--cdp-port", type=int, default=9222)
    ap.add_argument("--cdp-host", default="http://localhost")
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--timeout", type=int, default=2400, help="batch run cap in seconds")
    args = ap.parse_args()

    cdp_host = f"{args.cdp_host}:{args.cdp_port}"
    url = f"{args.base_url}/batch-detect.html?method={args.method}"

    out_csv = os.path.join("runs", "resources", args.platform, f"resources_{args.platform}_{args.method}_n179.csv")
    out_json = os.path.join("runs", "batch_results", f"{args.platform}_{args.method}_n179.json")
    os.makedirs(os.path.dirname(out_csv), exist_ok=True)
    os.makedirs(os.path.dirname(out_json), exist_ok=True)

    target = pick_target(cdp_host, url_hint="batch-detect.html")
    print(f"[INFO] CDP target: {target.get('id')} ({target.get('title', '')[:60]})")
    print(f"[INFO] Navigating to: {url}")

    try:
        with urllib.request.urlopen(f"{cdp_host}/json/activate/{target['id']}", timeout=5) as r:
            r.read()
    except Exception as e:
        print(f"[WARN] activate failed (continuing): {e}")

    ws = WS(target["webSocketDebuggerUrl"])
    try:
        ws.ws.settimeout(15)
        ws.call("Page.enable")
        ws.ws.settimeout(120)
        ws.call("Page.navigate", {"url": url})

        print("[STEP] Waiting for window.__runBatch …")
        deadline = time.time() + 60
        while time.time() < deadline:
            try:
                if ws.js("(typeof window.__runBatch === 'function')"):
                    break
            except Exception:
                pass
            time.sleep(1)
        else:
            print("[ERR] window.__runBatch never appeared.")
            return 1

        ws.js("window.__runBatch()")
        print("[STEP] Batch started; polling progress …", flush=True)
        start = time.time()
        last_seen = -1
        consecutive_errors = 0
        target_id = target["id"]
        while time.time() - start < args.timeout:
            try:
                state = ws.js(
                    "(()=>({done:!!window.__BATCH_DONE__, n:(window.__BATCH_RESULTS__||[]).length}))()"
                )
                consecutive_errors = 0
            except Exception as e:
                consecutive_errors += 1
                print(f"  poll error #{consecutive_errors}: {e}; reconnecting …", flush=True)
                try:
                    ws.close()
                except Exception:
                    pass
                time.sleep(3)
                try:
                    with urllib.request.urlopen(f"{cdp_host}/json/activate/{target_id}", timeout=5) as r:
                        r.read()
                except Exception:
                    pass
                try:
                    pages = cdp_pages(cdp_host)
                    new_target = next((p for p in pages if p.get("id") == target_id), None)
                    if not new_target:
                        new_target = next((p for p in pages if "batch-detect.html" in (p.get("url") or "")), None)
                    if new_target:
                        ws = WS(new_target["webSocketDebuggerUrl"])
                        ws.ws.settimeout(120)
                        target_id = new_target["id"]
                        print(f"  reconnected to tab {target_id}", flush=True)
                except Exception as e2:
                    print(f"  reconnect failed: {e2}", flush=True)
                if consecutive_errors > 30:
                    print(f"[ERR] {consecutive_errors} consecutive errors, giving up.", flush=True)
                    return 1
                continue
            n = state["n"]
            if n != last_seen:
                print(f"  [t+{int(time.time()-start)}s] {n}/179", flush=True)
                last_seen = n
            if state["done"]:
                break
            time.sleep(3)
        else:
            print(f"[ERR] Bench timed out after {args.timeout}s.")
            return 1

        elapsed = time.time() - start
        print(f"[INFO] Bench done in {elapsed:.1f}s")

        ua = ws.js("navigator.userAgent")
        label = f"batch-detect {args.method} {args.platform} n=179"

        results_json = ws.js("JSON.stringify(window.__BATCH_RESULTS__ || [])")
        with open(out_json, "w", encoding="utf-8") as f:
            f.write(results_json)
        results = json.loads(results_json)
        print(f"[OK] Wrote {len(results)} sheets → {out_json}")

        csv_str = ws.js(
            """
            (() => {
              const ts = new Date().toISOString();
              const ua = """ + json.dumps(ua) + """;
              const label = """ + json.dumps(label) + """;
              const header = "t_ms,run_idx,sheet_idx,js_heap_used_mb,js_heap_total_mb,js_heap_limit_mb,wasm_heap_cv_mb,wasm_heap_yolo_mb,event_loop_lag_ms,cpu_load_proxy,platform,timestamp,session_label";
              const rows = (window.ResourceMonitor.samples || []).map(s =>
                `${s.t_ms.toFixed(1)},${s.run_idx},${s.sheet_idx},${s.js_heap_used_mb.toFixed(2)},${s.js_heap_total_mb.toFixed(2)},${s.js_heap_limit_mb.toFixed(2)},${s.wasm_heap_cv_mb.toFixed(2)},${s.wasm_heap_yolo_mb.toFixed(2)},${s.event_loop_lag_ms.toFixed(2)},${s.cpu_load_proxy.toFixed(4)},"${ua.replace(/"/g,"'")}",${ts},"${label}"`
              );
              return header + "\\n" + rows.join("\\n") + "\\n";
            })()
            """
        )
        with open(out_csv, "w", encoding="utf-8", newline="") as f:
            f.write(csv_str)
        nrows = csv_str.count("\n") - 1
        print(f"[OK] Wrote {nrows} resource samples → {out_csv}")

        return 0
    finally:
        ws.close()


if __name__ == "__main__":
    sys.exit(main())
