#!/usr/bin/env python3
"""
OMR Benchmark Server — serves web/ with Cross-Origin isolation headers.

Usage:
    python web/server.py [port] [--https] [--dataset=PATH]

Flags:
    --https        Generate & use a self-signed TLS cert (requires OpenSSL in PATH).
                   Lets Chrome Mobile accept SharedArrayBuffer without ADB tunnel.
    --dataset=PATH Alias /dataset/* to PATH (default: ../Dataset_OMR_classified).
                   Lets batch-detect.html fetch images from outside web/.

Required headers (SharedArrayBuffer / crossOriginIsolated):
    Cross-Origin-Opener-Policy:   same-origin
    Cross-Origin-Embedder-Policy: require-corp
    Cross-Origin-Resource-Policy: cross-origin
"""

import http.server
import socketserver
import sys
import os
import socket
import ssl
import subprocess
import tempfile
import urllib.parse
import posixpath

# ── Args ──────────────────────────────────────────────────────────────────────
_args = sys.argv[1:]
PORT = 8080
for a in _args:
    if a.isdigit():
        PORT = int(a)
USE_HTTPS = "--https" in _args

WEB_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DATASET = os.path.abspath(os.path.join(WEB_DIR, "..", "Dataset_OMR_classified"))
DATASET_DIR = next((a.split("=", 1)[1] for a in _args if a.startswith("--dataset=")),
                   DEFAULT_DATASET)
DATASET_DIR = os.path.abspath(DATASET_DIR)

# Serve from the directory where this script lives (web/)
os.chdir(WEB_DIR)


# ── Request handler ───────────────────────────────────────────────────────────
class IsolatedHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
        self.send_header("Cross-Origin-Resource-Policy", "cross-origin")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def log_message(self, fmt, *args):
        # Suppress 200s to reduce noise; show errors.
        if args and str(args[1]) != "200":
            super().log_message(fmt, *args)

    def translate_path(self, path):
        p = urllib.parse.urlsplit(path).path
        p = urllib.parse.unquote(p)
        p = posixpath.normpath(p)
        if p.startswith("/dataset/") or p == "/dataset":
            rel = p[len("/dataset"):].lstrip("/")
            return os.path.join(DATASET_DIR, rel.replace("/", os.sep))
        return super().translate_path(path)


# ── Helpers ───────────────────────────────────────────────────────────────────
def get_lan_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "?.?.?.?"


def gen_self_signed_cert(ip, cert_path):
    """Generate self-signed PEM cert+key via openssl subprocess. Returns True on success."""
    try:
        subprocess.run(
            [
                "openssl", "req", "-x509", "-newkey", "rsa:2048",
                "-keyout", cert_path, "-out", cert_path,
                "-days", "30", "-nodes",
                "-subj", f"/CN={ip}",
            ],
            check=True, capture_output=True,
        )
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False


# ── Main ──────────────────────────────────────────────────────────────────────
lan_ip = get_lan_ip()
scheme = "https" if USE_HTTPS else "http"

with socketserver.TCPServer(("", PORT), IsolatedHandler) as httpd:
    if USE_HTTPS:
        cert_path = os.path.join(tempfile.gettempdir(), f"omr_bench_{PORT}.pem")
        if not os.path.exists(cert_path):
            print(f"[HTTPS] Generating self-signed cert for {lan_ip} …")
            if not gen_self_signed_cert(lan_ip, cert_path):
                print("[HTTPS] ERROR: openssl not found in PATH.")
                print("        Install Git-for-Windows (includes openssl) or use ADB tunnel instead.")
                print(f"        > adb reverse tcp:{PORT} tcp:{PORT}")
                sys.exit(1)
            print(f"[HTTPS] Cert saved: {cert_path}")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert_path)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)

    W = 58
    print(f"\n{'='*W}")
    print(f"  OMR Benchmark Server ({'HTTPS' if USE_HTTPS else 'HTTP'})  —  port {PORT}")
    print(f"{'='*W}")
    print(f"  PC local  :  {scheme}://localhost:{PORT}")
    print(f"  LAN mobile:  {scheme}://{lan_ip}:{PORT}")
    print(f"  Headers   :  COOP=same-origin  COEP=require-corp  CORP=cross-origin")
    print(f"  Dataset   :  /dataset/  ->  {DATASET_DIR}")
    print(f"{'='*W}")

    if USE_HTTPS:
        print(f"\n  [HTTPS MODE]")
        print(f"  1. Open {scheme}://{lan_ip}:{PORT} in Chrome Mobile")
        print(f"  2. Chrome will warn about the self-signed cert — tap")
        print(f"     'Advanced' → 'Proceed' to trust it for this session.")
        print(f"  3. Verify in DevTools console:")
        print(f"     window.crossOriginIsolated  // expect true")
    else:
        print(f"\n  MOBILE SETUP — choose one option:")
        print()
        print(f"  [A] ADB reverse tunnel  (recommended — requires USB + ADB)")
        print(f"      > adb reverse tcp:{PORT} tcp:{PORT}")
        print(f"      Open http://localhost:{PORT} on Chrome Mobile.")
        print(f"      Localhost is treated as secure → SAB enabled.")
        print()
        print(f"  [B] Chrome flag  (no USB needed)")
        print(f"      On the phone, open:")
        print(f"        chrome://flags/#unsafely-treat-insecure-origin-as-secure")
        print(f"      Add:  http://{lan_ip}:{PORT}")
        print(f"      Tap 'Relaunch', then open http://{lan_ip}:{PORT}.")
        print()
        print(f"  [C] HTTPS mode  (wireless, no USB)")
        print(f"      > python server.py {PORT} --https")
        print(f"      Trust the self-signed cert when Chrome warns.")

    print(f"\n  VERIFY in Chrome DevTools (F12) console before benchmarking:")
    print(f"    window.crossOriginIsolated               // must be: true")
    print(f"    typeof SharedArrayBuffer                 // must be: 'function'")
    print(f"    WebAssembly.validate(new Uint8Array([")
    print(f"      0,97,115,109,1,0,0,0,1,5,1,96,0,1,")
    print(f"      123,3,2,1,0,10,10,1,8,0,65,0,253,15,253,98,11")
    print(f"    ]))                                      // must be: true (SIMD128)")
    print()
    print(f"  The app's header bar also shows isolation status on load.")
    print(f"\n  Press Ctrl+C to stop.\n")

    httpd.serve_forever()
