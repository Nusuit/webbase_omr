#!/usr/bin/env pwsh
# Run full benchmark pipeline for acer_nitro5 (cv + yolo)
# Usage: cd webbase_omr && pwsh scripts/run_acer_nitro5.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root

$Chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$UserDataDir = "$env:TEMP\chrome_cdp_bench"
$Port = 9222

New-Item -ItemType Directory -Force -Path "logs" | Out-Null

Write-Host "=== acer_nitro5 benchmark pipeline ===" -ForegroundColor Cyan

# ── 1. Kill any leftover chrome with CDP port ─────────────────────────────────
Write-Host "[1] Checking for existing Chrome on port $Port..."
$existing = netstat -ano 2>$null | Select-String ":$Port "
if ($existing) {
    $pids = $existing | ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique
    foreach ($procId in $pids) {
        try { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue } catch {}
    }
    Start-Sleep -Seconds 2
}

# ── 2. Start web server ───────────────────────────────────────────────────────
Write-Host "[2] Starting web server on :8080..."
$server = Start-Process python -ArgumentList "web/server.py","8080","--dataset=Dataset_OMR_classified" `
    -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "logs/server_bench.log" `
    -RedirectStandardError "logs/server_bench_err.log"
Write-Host "    server PID=$($server.Id)"
Start-Sleep -Seconds 3

# ── 3. Launch Chrome with CDP ─────────────────────────────────────────────────
Write-Host "[3] Launching Chrome with CDP on port $Port..."
$chrome = Start-Process $Chrome `
    -ArgumentList "--remote-debugging-port=$Port",
                  "--user-data-dir=$UserDataDir",
                  "--no-first-run",
                  "--no-default-browser-check",
                  "--disable-background-timer-throttling",
                  "--disable-renderer-backgrounding",
                  "--disable-backgrounding-occluded-windows",
                  "http://localhost:8080/batch-detect.html" `
    -PassThru
Write-Host "    Chrome PID=$($chrome.Id)"
Start-Sleep -Seconds 5

# Verify CDP is responding
try {
    $pages = Invoke-RestMethod "http://localhost:$Port/json" -TimeoutSec 10
    Write-Host "    CDP OK: $($pages.Count) tab(s)" -ForegroundColor Green
} catch {
    Write-Host "    CDP not responding: $_" -ForegroundColor Red
    exit 1
}

# ── 4. Run CV benchmark ───────────────────────────────────────────────────────
Write-Host "`n[4] Running CV benchmark..." -ForegroundColor Yellow
$t0 = Get-Date
python scripts/drive_batch.py acer_nitro5 cv `
    --cdp-port $Port `
    --chrome-pid $chrome.Id `
    --log-file "logs/drive_acer_nitro5_cv.log"
$elapsed = ((Get-Date) - $t0).TotalSeconds
Write-Host "    CV done in $([math]::Round($elapsed,1))s" -ForegroundColor Green

# ── 5. Reload page before YOLO ────────────────────────────────────────────────
Write-Host "`n[5] Reloading page for YOLO run..."
Start-Sleep -Seconds 3

# ── 6. Run YOLO benchmark ─────────────────────────────────────────────────────
Write-Host "`n[6] Running YOLO benchmark..." -ForegroundColor Yellow
$t0 = Get-Date
python scripts/drive_batch.py acer_nitro5 yolo `
    --cdp-port $Port `
    --chrome-pid $chrome.Id `
    --log-file "logs/drive_acer_nitro5_yolo.log"
$elapsed = ((Get-Date) - $t0).TotalSeconds
Write-Host "    YOLO done in $([math]::Round($elapsed,1))s" -ForegroundColor Green

# ── 7. Cleanup ────────────────────────────────────────────────────────────────
Write-Host "`n[7] Stopping Chrome and server..."
try { Stop-Process -Id $chrome.Id -Force -ErrorAction SilentlyContinue } catch {}
try { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue } catch {}

Write-Host "`n=== Done! Output files ===" -ForegroundColor Cyan
Get-ChildItem "runs/resources/acer_nitro5" -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "  $($_.FullName)" }
Get-ChildItem "runs/batch_results" -Filter "acer_nitro5*" -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "  $($_.FullName)" }
Write-Host "`n  Logs:"
Get-ChildItem "logs" -Filter "*acer_nitro5*" -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "  $($_.FullName)" }
Get-ChildItem "logs" -Filter "server_bench*" -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "  $($_.FullName)" }
