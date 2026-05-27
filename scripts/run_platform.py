#!/usr/bin/env python3
"""
OMR benchmark runner — Windows / macOS / Linux.
Run from project root: python scripts/run_platform.py <platform_name>

Prerequisites: pip install websocket-client psutil matplotlib
Chrome must be installed (or set CHROME=/path/to/chrome).
"""
import json, os, platform, shutil, subprocess, sys, tempfile, time, urllib.request

CDP_PORT    = 9222
SERVER_PORT = 8080

CHROME_PATHS = {
    "Windows": [r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"],
    "Darwin":  ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium"],
    "Linux":   ["/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium-browser", "/usr/bin/chromium"],
}

def find_chrome():
    exe = os.environ.get("CHROME")
    if exe and os.path.exists(exe):
        return exe
    for p in CHROME_PATHS.get(platform.system(), []):
        if os.path.exists(p):
            return p
    found = shutil.which("google-chrome") or shutil.which("chromium")
    if found:
        return found
    raise RuntimeError("Chrome not found. Set CHROME=/path/to/chrome env var.")

def cdp_ready(port, timeout=30):
    for _ in range(timeout):
        try:
            with urllib.request.urlopen(f"http://localhost:{port}/json", timeout=2) as r:
                if json.loads(r.read()):
                    return True
        except Exception:
            pass
        time.sleep(1)
    return False

def kill(proc):
    if proc and proc.poll() is None:
        proc.terminate()
        try: proc.wait(timeout=5)
        except Exception: proc.kill()

def main():
    if len(sys.argv) < 2:
        print("Usage: python scripts/run_platform.py <platform_name>")
        sys.exit(1)
    plat = sys.argv[1]

    if not os.path.exists("web/server.py"):
        print("Run from project root (webbase_omr/)."); sys.exit(1)

    subprocess.check_call([sys.executable, "-m", "pip", "install",
                           "websocket-client", "psutil", "matplotlib", "-q"])

    os.makedirs("logs", exist_ok=True)
    chrome = find_chrome()
    uddir  = os.path.join(tempfile.gettempdir(), "omr_bench_cdp")
    print(f"[platform] {plat}  [chrome] {chrome}")

    srv_log = open("logs/server_bench.log", "w", encoding="utf-8")
    srv_err = open("logs/server_bench_err.log", "w", encoding="utf-8")
    server  = subprocess.Popen(
        [sys.executable, "web/server.py", str(SERVER_PORT), "--dataset=Dataset_OMR_classified"],
        stdout=srv_log, stderr=srv_err)
    time.sleep(3)

    chrome_proc = subprocess.Popen([
        chrome,
        f"--remote-debugging-port={CDP_PORT}",
        f"--user-data-dir={uddir}",
        "--no-first-run", "--no-default-browser-check",
        "--disable-background-timer-throttling",
        "--disable-renderer-backgrounding",
        "--disable-backgrounding-occluded-windows",
        f"http://localhost:{SERVER_PORT}/batch-detect.html",
    ])
    print(f"[server] PID={server.pid}  [chrome] PID={chrome_proc.pid}")

    if not cdp_ready(CDP_PORT):
        print("CDP not responding."); kill(chrome_proc); kill(server); sys.exit(1)
    print("[CDP] OK")

    try:
        for method in ["cv", "yolo"]:
            print(f"\n[{method.upper()}] starting...")
            r = subprocess.run([
                sys.executable, "scripts/drive_batch.py",
                plat, method,
                "--cdp-port", str(CDP_PORT),
                "--chrome-pid", str(chrome_proc.pid),
                "--log-file", f"logs/drive_{plat}_{method}.log",
            ])
            if r.returncode != 0:
                print(f"[{method.upper()}] FAILED"); break
            time.sleep(3)

        subprocess.run([sys.executable, "scripts/plot_resources.py",
                        f"runs/resources/{plat}/"], check=False)
    finally:
        kill(chrome_proc); kill(server)
        srv_log.close(); srv_err.close()

    print(f"\n=== outputs ===")
    for d, fs in [
        (f"runs/resources/{plat}", os.listdir(f"runs/resources/{plat}") if os.path.exists(f"runs/resources/{plat}") else []),
        ("runs/batch_results",     [f for f in os.listdir("runs/batch_results") if plat in f]),
        ("logs",                   [f for f in os.listdir("logs") if plat in f or "server" in f]),
    ]:
        for f in sorted(fs):
            print(f"  {d}/{f}")

if __name__ == "__main__":
    main()
