"""Clean re-run for PC gaming: restart a fresh Chrome before EVERY measurement
so per-run RAM is not contaminated by cross-run leak. Profile dir is reused so
the WebGPU shader cache stays warm (latency stays representative).

Overwrites the same output tags (pcg_cv_rN / pcg_yc_rN) used by the aggregation.
"""
import json, os, subprocess, sys, time, urllib.request
import psutil

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PROFILE = os.path.join(os.getcwd(), ".chrome-pcgaming")
CDP = "http://localhost:9222"
URL = "http://localhost:8080/batch-detect.html"


def cdp_up():
    try:
        with urllib.request.urlopen(CDP + "/json/version", timeout=2) as r:
            return b"Browser" in r.read()
    except Exception:
        return False


def find_browser_pid():
    for p in psutil.process_iter(["pid", "name"]):
        try:
            if p.name().lower() == "chrome.exe":
                cl = " ".join(p.cmdline())
                if ".chrome-pcgaming" in cl and "--type=" not in cl:
                    return p.pid
        except Exception:
            pass
    return None


def kill_chrome():
    for p in list(psutil.process_iter(["pid", "name"])):
        try:
            if p.name().lower() == "chrome.exe" and ".chrome-pcgaming" in " ".join(p.cmdline()):
                for c in p.children(recursive=True):
                    try: c.kill()
                    except Exception: pass
                p.kill()
        except Exception:
            pass
    # wait for port free
    for _ in range(30):
        if not cdp_up():
            return
        time.sleep(0.5)


def launch_chrome():
    args = [CHROME, "--headless=new", "--remote-debugging-port=9222",
            f"--user-data-dir={PROFILE}", "--no-first-run", "--no-default-browser-check",
            "--enable-unsafe-webgpu", "--enable-features=Vulkan", URL]
    subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(60):
        if cdp_up():
            break
        time.sleep(0.5)
    else:
        raise RuntimeError("CDP never came up")
    time.sleep(2)  # let page settle
    pid = None
    for _ in range(20):
        pid = find_browser_pid()
        if pid:
            break
        time.sleep(0.5)
    if not pid:
        raise RuntimeError("browser pid not found")
    return pid


def run_one(method, tag):
    kill_chrome()
    pid = launch_chrome()
    print(f"\n##### {tag}: fresh Chrome pid={pid} #####", flush=True)
    rc = subprocess.call([sys.executable, "scripts/drive_batch.py", "pcgaming", method,
                          "--cdp-port", "9222", "--chrome-pid", str(pid),
                          "--output-tag", tag])
    print(f"##### {tag}: drive_batch rc={rc} #####", flush=True)
    return rc


def main():
    for r in (1, 2, 3, 4, 5):
        run_one("cv", f"pcg_cv_r{r}")
        run_one("yolocorner", f"pcg_yc_r{r}")
    kill_chrome()
    print("\n===== CLEAN RERUN DONE =====", flush=True)


if __name__ == "__main__":
    sys.exit(main())
