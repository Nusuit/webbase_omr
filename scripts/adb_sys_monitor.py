"""Sample device-level CPU% and RAM (PSS) of Chrome via adb during a benchmark.

Writes a _sys.csv with columns matching the PC SysMonitor convention:
    t_ms,cpu_pct,ram_mb

- cpu_pct = sum of %CPU across all com.android.chrome* processes (multi-core, may exceed 100).
- ram_mb  = dumpsys meminfo TOTAL PSS for the Chrome package (MB).

Usage:
    python scripts/adb_sys_monitor.py <serial> <out_csv> [--pkg com.android.chrome] [--max-s 200]

Runs until --max-s elapses or it receives SIGTERM/KeyboardInterrupt; flushes on exit.
"""
import argparse, re, signal, subprocess, sys, time

class _Stop(Exception):
    pass

def _on_term(signum, frame):
    raise _Stop()

def adb(serial, *args, timeout=15):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True,
                          text=True, timeout=timeout).stdout

def sample_cpu(serial, pkg):
    # top -n2 -d1 : second frame carries real %CPU deltas computed by top
    out = adb(serial, "shell", "top", "-b", "-n", "2", "-d", "1", timeout=20)
    # Keep only the last frame (after the final header line containing 'PID')
    frames = out.split("\n")
    last_hdr = max((i for i, l in enumerate(frames) if re.search(r"\bPID\b", l)), default=0)
    total = 0.0
    for line in frames[last_hdr + 1:]:
        if pkg not in line:
            continue
        cols = line.split()
        # locate the %CPU column: it's the numeric col right after state (R/S/D...)
        for j, c in enumerate(cols):
            if c in ("R", "S", "D", "T", "Z", "I") and j + 1 < len(cols):
                try:
                    total += float(cols[j + 1])
                except ValueError:
                    pass
                break
    return total

def sample_ram(serial, pkg):
    out = adb(serial, "shell", "dumpsys", "meminfo", pkg, timeout=20)
    m = re.search(r"TOTAL PSS:\s*(\d+)", out)
    return (int(m.group(1)) / 1024.0) if m else 0.0  # KB -> MB

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("serial")
    ap.add_argument("out_csv")
    ap.add_argument("--pkg", default="com.android.chrome")
    ap.add_argument("--max-s", type=float, default=200.0)
    args = ap.parse_args()

    signal.signal(signal.SIGTERM, _on_term)
    t0 = time.time()
    n = 0
    # Incremental write + flush so data survives a hard kill (Windows SIGTERM
    # may terminate Python before any finally/handler runs).
    f = open(args.out_csv, "w", encoding="utf-8", newline="")
    f.write("t_ms,cpu_pct,ram_mb\n"); f.flush()
    try:
        while time.time() - t0 < args.max_s:
            t_ms = (time.time() - t0) * 1000.0
            try:
                cpu = sample_cpu(args.serial, args.pkg)
                ram = sample_ram(args.serial, args.pkg)
            except subprocess.TimeoutExpired:
                continue
            f.write(f"{t_ms:.1f},{cpu:.1f},{ram:.1f}\n"); f.flush()
            n += 1
    except (KeyboardInterrupt, _Stop):
        pass
    finally:
        f.close()
        print(f"[OK] {n} samples -> {args.out_csv}", file=sys.stderr)

if __name__ == "__main__":
    main()
