"""Minimal robust CDP runner: open a FRESH tab, run __runBatch, save results.

Avoids stale/frozen tabs in a busy Chrome by creating a new target via the
browser-level CDP endpoint, activating it (foreground so Android does not freeze
the renderer), then polling window.__BATCH_DONE__ / __BATCH_RESULTS__.

Usage: python scripts/_cdp_run_fresh.py <cdp_port> <method> <out_json>
"""
import json, sys, time, urllib.request
import websocket  # type: ignore

PORT, METHOD, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
HOST = f"http://localhost:{PORT}"
URL = f"http://localhost:8080/batch-detect.html?method={METHOD}"


def http(path):
    return json.load(urllib.request.urlopen(f"{HOST}{path}", timeout=10))


class WS:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, suppress_origin=True, timeout=300)
        self.seq = 0

    def call(self, method, params=None):
        self.seq += 1
        m = {"id": self.seq, "method": method}
        if params:
            m["params"] = params
        self.ws.send(json.dumps(m))
        while True:
            r = json.loads(self.ws.recv())
            if r.get("id") == self.seq:
                if "error" in r:
                    raise RuntimeError(f"{method}: {r['error']}")
                return r.get("result")

    def js(self, expr, await_promise=False):
        r = self.call("Runtime.evaluate", {"expression": expr, "returnByValue": True,
                                            "awaitPromise": await_promise}).get("result", {})
        return r.get("value")


# 1. Create a fresh tab via the browser-level CDP endpoint.
ver = http("/json/version")
brws = WS(ver["webSocketDebuggerUrl"])
tid = brws.call("Target.createTarget", {"url": URL})["targetId"]
print(f"[fresh] created target {tid}", flush=True)
try:
    urllib.request.urlopen(f"{HOST}/json/activate/{tid}", timeout=5).read()
except Exception as e:
    print("[warn] activate:", e)

# 2. Connect to the new tab and explicitly navigate (Android ignores the
#    createTarget url param, leaving the tab on about:blank).
time.sleep(2)
page = next(p for p in http("/json") if p.get("id") == tid)
ws = WS(page["webSocketDebuggerUrl"])
ws.call("Page.navigate", {"url": URL})
print(f"[fresh] navigated to {URL}", flush=True)
time.sleep(3)

# 3. Wait for __runBatch (page + worker init). Re-activate each poll to keep it
#    foreground so Android Chrome does not freeze the renderer.
print("[wait] for window.__runBatch ...", flush=True)
deadline = time.time() + 120
while time.time() < deadline:
    try:
        if ws.js("typeof window.__runBatch === 'function'"):
            break
    except Exception as e:
        print("[poll]", e)
    try:
        urllib.request.urlopen(f"{HOST}/json/activate/{tid}", timeout=5).read()
    except Exception:
        pass
    time.sleep(2)
else:
    print("[ERR] __runBatch never appeared"); sys.exit(1)

# 4. Start the batch.
ws.js("window.__runBatch()")
print("[run] batch started", flush=True)
start = time.time()
last = -1
while time.time() - start < 1200:
    try:
        urllib.request.urlopen(f"{HOST}/json/activate/{tid}", timeout=5).read()
    except Exception:
        pass
    try:
        done = ws.js("!!window.__BATCH_DONE__")
        n = ws.js("(window.__BATCH_RESULTS__||[]).length") or 0
    except Exception as e:
        print("[poll]", e); time.sleep(3); continue
    if n != last:
        print(f"  [t+{int(time.time()-start)}s] {n}/179", flush=True)
        last = n
    if done:
        break
    time.sleep(3)

# 5. Export.
results_json = ws.js("JSON.stringify(window.__BATCH_RESULTS__ || [])")
with open(OUT, "w", encoding="utf-8") as f:
    f.write(results_json)
print(f"[OK] wrote {len(json.loads(results_json))} sheets -> {OUT}", flush=True)
try:
    brws.call("Target.closeTarget", {"targetId": tid})
except Exception:
    pass
