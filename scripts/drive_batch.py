"""Drive Chrome (PC or Android) via CDP to run batch-detect.html on n=179 dataset.

Exports the resource sample CSV, per-sheet batch results JSON, a system-level
CSV with real CPU%/RAM from the Chrome renderer process, and a full log file.

Prerequisites (PC):
  - launch Chrome with: chrome --remote-debugging-port=9222 --user-data-dir=...
  - laptop: python web/server.py 8080 --dataset=Dataset_OMR_classified

Prerequisites (Mobile via USB):
  - adb reverse tcp:8080 tcp:8080
  - adb forward tcp:9222 localabstract:chrome_devtools_remote
  - laptop: python web/server.py 8080 --dataset=Dataset_OMR_classified

Usage:
  python scripts/drive_batch.py <platform> <method> [--cdp-port 9222]
      [--base-url http://localhost:8080] [--chrome-pid PID] [--log-file PATH]
      [--wasm-variant baseline|simd|threads|auto]

  platform: any string (e.g. pc, mobile, acer_nitro5)
  method:   cv | yolo

Outputs:
  runs/resources/<platform>/resources_<platform>_<method>_n179.csv      (in-browser)
  runs/resources/<platform>/resources_<platform>_<method>_n179_sys.csv  (system CPU%/RAM)
  runs/batch_results/<platform>_<method>_n179.json
  logs/drive_<platform>_<method>.log   (if --log-file not specified, auto-named)
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import urllib.request
import urllib.parse

import websocket  # type: ignore

try:
    import psutil  # type: ignore
    HAS_PSUTIL = True
except ImportError:
    HAS_PSUTIL = False


# ── Tee logger ────────────────────────────────────────────────────────────────
class _Tee:
    """Write to both stdout and a log file simultaneously."""
    def __init__(self, log_path: str):
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        self._file = open(log_path, "w", encoding="utf-8", buffering=1)
        self._orig = sys.__stdout__

    def write(self, s: str):
        self._orig.write(s)
        self._file.write(s)

    def flush(self):
        self._orig.flush()
        self._file.flush()

    def close(self):
        self._file.close()
        sys.stdout = self._orig


# ── System monitor (psutil) ───────────────────────────────────────────────────
class SysMonitor:
    """Sample Chrome renderer process CPU% and RAM (RSS) via psutil.

    CPU% here is per-process across all threads (main thread + Web Workers),
    matching what Chrome Task Manager shows for the tab's renderer process.
    Value > 100 is possible on multi-core (e.g. 400% = 4 cores saturated).
    """

    def __init__(self, interval_s: float = 1.0, chrome_pid: int | None = None):
        self.interval = interval_s
        self.chrome_pid = chrome_pid
        self.samples: list[dict] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._t0: float = 0.0
        self._procs: list = []

    def _find_procs(self) -> list:
        """Locate Chrome renderer child processes of the given browser PID."""
        procs = []
        if self.chrome_pid:
            try:
                parent = psutil.Process(self.chrome_pid)
                for p in parent.children(recursive=True):
                    try:
                        cmdline = " ".join(p.cmdline())
                        if "--type=renderer" in cmdline and "--extension-process" not in cmdline:
                            procs.append(p)
                    except (psutil.NoSuchProcess, psutil.AccessDenied):
                        pass
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                pass
        if not procs:
            # Fallback: scan all Chrome processes
            for p in psutil.process_iter(["pid", "name"]):
                try:
                    if p.name().lower() in ("chrome.exe", "chromium.exe", "chrome", "chromium"):
                        cmdline = " ".join(p.cmdline())
                        if "--type=renderer" in cmdline and "--extension-process" not in cmdline:
                            procs.append(p)
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
        return procs

    def start(self):
        if not HAS_PSUTIL:
            print("[SysMonitor] psutil not installed; skipping system metrics.")
            return
        self._t0 = time.time()
        self._procs = self._find_procs()
        pids = [p.pid for p in self._procs]
        print(f"[SysMonitor] Monitoring {len(self._procs)} renderer process(es): PIDs={pids}")
        for p in self._procs:
            try:
                p.cpu_percent(interval=None)  # prime (first call always returns 0)
            except Exception:
                pass
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True, name="SysMonitor")
        self._thread.start()

    def _run(self):
        time.sleep(self.interval)  # let cpu_percent stabilize after priming
        while not self._stop.is_set():
            t_ms = (time.time() - self._t0) * 1000
            cpu_total = 0.0
            ram_mb = 0.0
            alive = []
            for p in self._procs:
                try:
                    cpu_total += p.cpu_percent(interval=None)
                    ram_mb += p.memory_info().rss / (1024 * 1024)
                    alive.append(p)
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
            self._procs = alive
            self.samples.append({"t_ms": t_ms, "cpu_pct": cpu_total, "ram_mb": ram_mb})
            time.sleep(self.interval)

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=5)

    def write_csv(self, path: str) -> int:
        if not self.samples:
            print("[SysMonitor] No system samples collected.")
            return 0
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write("t_ms,cpu_pct,ram_mb\n")
            for s in self.samples:
                f.write(f"{s['t_ms']:.1f},{s['cpu_pct']:.2f},{s['ram_mb']:.2f}\n")
        return len(self.samples)


# ── CDP helpers ───────────────────────────────────────────────────────────────
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


# ── Main ──────────────────────────────────────────────────────────────────────
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("platform")
    ap.add_argument("method", choices=["cv", "yolo", "corners", "yolocorner", "yolocornerbbox"])
    ap.add_argument("--cdp-port", type=int, default=9222)
    ap.add_argument("--cdp-host", default="http://localhost")
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--timeout", type=int, default=3600, help="batch run cap in seconds")
    ap.add_argument("--chrome-pid", type=int, default=None,
                    help="PID of Chrome browser process (for psutil CPU/RAM monitoring)")
    ap.add_argument("--log-file", default=None,
                    help="Path to log file. Defaults to logs/drive_<platform>_<method>.log")
    ap.add_argument("--yolo-pad", type=float, default=None,
                    help="Forward YOLO bbox expansion fraction to worker-yolo.js")
    ap.add_argument("--yolo-mask", choices=["mask", "raw", "hint"], default=None,
                    help="Forward YOLO preprocessing mode to worker-yolo.js")
    ap.add_argument("--yolo-fallback", choices=["none", "bestdiag"], default=None,
                    help="Forward YOLO diagnostic fallback mode to worker-yolo.js")
    ap.add_argument("--output-tag", default=None,
                    help="Optional output stem suffix instead of <platform>_<method>_n179")
    ap.add_argument("--wasm-variant", choices=["baseline", "simd", "threads", "auto"], default="auto",
                    help="Force OMR WASM variant for Web runs. Default: auto.")
    args = ap.parse_args()

    # ── Setup log file (tee stdout) ──────────────────────────────────────────
    log_path = args.log_file or os.path.join(
        "logs", f"drive_{args.platform}_{args.method}.log"
    )
    os.makedirs(os.path.dirname(log_path), exist_ok=True)
    tee = _Tee(log_path)
    sys.stdout = tee
    print(f"[LOG] Writing to {log_path}")
    print(f"[LOG] Started at {time.strftime('%Y-%m-%d %H:%M:%S')}")

    cdp_host = f"{args.cdp_host}:{args.cdp_port}"
    page_params = {"method": args.method}
    if args.wasm_variant != "auto":
        page_params["variant"] = args.wasm_variant
    if args.method == "yolo":
        if args.yolo_pad is not None:
            page_params["pad"] = str(args.yolo_pad)
        if args.yolo_mask is not None:
            page_params["mask"] = args.yolo_mask
        if args.yolo_fallback is not None:
            page_params["fallback"] = args.yolo_fallback
    if args.method == "yolocornerbbox" and args.yolo_pad is not None:
        page_params["pad"] = str(args.yolo_pad)
    url = f"{args.base_url}/batch-detect.html?{urllib.parse.urlencode(page_params)}"

    stem = args.output_tag or f"{args.platform}_{args.method}_n179"
    out_csv = os.path.join("runs", "resources", args.platform, f"resources_{stem}.csv")
    out_sys_csv = os.path.join("runs", "resources", args.platform, f"resources_{stem}_sys.csv")
    out_json = os.path.join("runs", "batch_results", f"{stem}.json")
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
    sys_mon = SysMonitor(interval_s=1.0, chrome_pid=args.chrome_pid)

    try:
        ws.ws.settimeout(15)
        ws.call("Page.enable")
        ws.ws.settimeout(120)
        ws.call("Page.navigate", {"url": url})

        print("[STEP] Waiting for window.__runBatch ...")
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

        sys_mon.start()
        ws.js("window.__runBatch()")
        print("[STEP] Batch started; polling progress ...", flush=True)
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
                print(f"  poll error #{consecutive_errors}: {e}; reconnecting ...", flush=True)
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
                        new_target = next(
                            (p for p in pages if "batch-detect.html" in (p.get("url") or "")), None
                        )
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
                elapsed = int(time.time() - start)
                # Show system metrics inline with progress
                if sys_mon.samples:
                    s = sys_mon.samples[-1]
                    print(f"  [t+{elapsed}s] {n}/179  cpu={s['cpu_pct']:.1f}%  ram={s['ram_mb']:.0f}MB",
                          flush=True)
                else:
                    print(f"  [t+{elapsed}s] {n}/179", flush=True)
                last_seen = n
            if state["done"]:
                break
            time.sleep(3)
        else:
            print(f"[ERR] Bench timed out after {args.timeout}s.")
            return 1

        sys_mon.stop()
        elapsed = time.time() - start
        print(f"[INFO] Bench done in {elapsed:.1f}s")

        ua = ws.js("navigator.userAgent")
        label = f"batch-detect {args.method} {args.platform} n=179"

        # ── Export per-sheet results JSON ────────────────────────────────────
        results_json = ws.js("JSON.stringify(window.__BATCH_RESULTS__ || [])")
        with open(out_json, "w", encoding="utf-8") as f:
            f.write(results_json)
        results = json.loads(results_json)
        print(f"[OK] Wrote {len(results)} sheets -> {out_json}")

        # ── Export in-browser resource CSV ───────────────────────────────────
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
        print(f"[OK] Wrote {nrows} in-browser samples -> {out_csv}")

        # ── Export system CPU%/RAM CSV ────────────────────────────────────────
        n_sys = sys_mon.write_csv(out_sys_csv)
        print(f"[OK] Wrote {n_sys} system samples -> {out_sys_csv}")

        print(f"[LOG] Finished at {time.strftime('%Y-%m-%d %H:%M:%S')}")
        return 0

    finally:
        sys_mon.stop()
        ws.close()
        tee.close()


if __name__ == "__main__":
    sys.exit(main())
