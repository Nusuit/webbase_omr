"""Drive phone Chrome via CDP to run batch-detect.html on the n=179 dataset.

Prerequisites:
  - adb reverse tcp:8080 tcp:8080  (so phone reaches laptop web/server.py)
  - adb forward tcp:9222 localabstract:chrome_devtools_remote
  - laptop: python web/server.py 8080 --dataset=Dataset_OMR_classified

Usage:
  python scripts/drive_mobile_batch.py cv   runs/resources/mobile/resources_mobile_cv_n179.csv
  python scripts/drive_mobile_batch.py yolo runs/resources/mobile/resources_mobile_yolo_n179.csv
"""
import json, sys, time, urllib.request
import websocket  # type: ignore

CDP = "http://localhost:9222"


def cdp_pages():
    with urllib.request.urlopen(f"{CDP}/json") as r:
        return json.loads(r.read())


def cdp_new(url):
    with urllib.request.urlopen(f"{CDP}/json/new?{url}") as r:
        return json.loads(r.read())


class WS:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, suppress_origin=True, timeout=120)
        self.seq = 0

    def call(self, method, params=None):
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

    def js(self, expr, await_promise=False):
        r = self.call("Runtime.evaluate", {
            "expression": expr,
            "awaitPromise": await_promise,
            "returnByValue": True,
        }).get("result", {})
        if r.get("subtype") == "error":
            raise RuntimeError(f"JS: {r.get('description')}")
        return r.get("value")

    def close(self):
        try: self.ws.close()
        except Exception: pass


def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    method = sys.argv[1]
    out_csv = sys.argv[2]
    url = f"http://localhost:8080/batch-detect.html?method={method}"

    # Reuse first page tab; navigate it to our URL (Chrome Android disables /json/new)
    pages = [p for p in cdp_pages() if p.get("type") == "page"]
    if not pages:
        print("[ERR] No tabs open in phone Chrome. Open any tab first."); sys.exit(1)
    page = pages[0]
    print(f"[INFO] Hijacking tab #{page.get('id')}: {page.get('title')[:60]}")
    print(f"[INFO] Will navigate to: {url}")

    ws = WS(page["webSocketDebuggerUrl"])
    try:
        ws.call("Page.enable")
        # Navigate (idempotent) and wait for ready
        ws.call("Page.navigate", {"url": url})
        print("[STEP] Waiting for page + worker init …")
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
            sys.exit(1)

        # Kick off the batch (non-await — it logs as it runs)
        ws.js("window.__runBatch()")
        print("[STEP] Batch started; polling progress …")
        start = time.time()
        last_seen = -1
        while time.time() - start < 3600:  # 1h cap
            try:
                state = ws.js(
                    "(()=>({done:!!window.__BATCH_DONE__, n:(window.__BATCH_RESULTS__||[]).length}))()"
                )
            except Exception as e:
                print(f"  poll error: {e}; retrying …"); time.sleep(2); continue
            n = state["n"]
            if n != last_seen:
                print(f"  [t+{int(time.time()-start)}s] {n}/179")
                last_seen = n
            if state["done"]:
                break
            time.sleep(5)
        else:
            print("[ERR] Bench timed out after 1h.")
            sys.exit(1)

        elapsed = time.time() - start
        print(f"[INFO] Done in {elapsed:.1f}s")

        ua = ws.js("navigator.userAgent")
        label = f"batch-detect {method} mobile n=179"
        csv_str = ws.js(f"""
          (() => {{
            const ts = new Date().toISOString();
            const ua = {json.dumps(ua)};
            const label = {json.dumps(label)};
            const header = "t_ms,run_idx,sheet_idx,js_heap_used_mb,js_heap_total_mb,js_heap_limit_mb,wasm_heap_cv_mb,wasm_heap_yolo_mb,event_loop_lag_ms,cpu_load_proxy,platform,timestamp,session_label";
            const rows = (window.ResourceMonitor.samples || []).map(s =>
              `${{s.t_ms.toFixed(1)}},${{s.run_idx}},${{s.sheet_idx}},${{s.js_heap_used_mb.toFixed(2)}},${{s.js_heap_total_mb.toFixed(2)}},${{s.js_heap_limit_mb.toFixed(2)}},${{s.wasm_heap_cv_mb.toFixed(2)}},${{s.wasm_heap_yolo_mb.toFixed(2)}},${{s.event_loop_lag_ms.toFixed(2)}},${{s.cpu_load_proxy.toFixed(4)}},"${{ua.replace(/"/g,"'")}}",${{ts}},"${{label}}"`
            );
            return header + "\\n" + rows.join("\\n") + "\\n";
          }})()
        """)

        with open(out_csv, "w", encoding="utf-8", newline="") as f:
            f.write(csv_str)
        nrows = csv_str.count("\n") - 1
        print(f"[OK] Wrote {nrows} samples → {out_csv}")
    finally:
        ws.close()


if __name__ == "__main__":
    main()
