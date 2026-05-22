# SIMD + Threading WASM Build Variants

## Tổng quan

Thêm hai WASM build variant mới (`omr_simd`, `omr_threads`) song song với bản baseline `omr`. Workers tự detect capability của browser và load đúng variant, không cần can thiệp thủ công.

---

## Files đã thay đổi

### `CMakeLists.txt`
Thêm hai cmake options:

| Option | Mặc định | Ý nghĩa |
|--------|----------|---------|
| `OPENCV_VARIANT` | `baseline` | Chọn `baseline` / `simd` / `threads` |
| `ENABLE_THREADS` | `OFF` | Tự động bật khi `OPENCV_VARIANT=threads` |

`OPENCV_VARIANT` drive:
- `OpenCV_DIR` → trỏ đến thư mục OpenCV tương ứng
- `OUTPUT_NAME` → tên output file (`omr` / `omr_simd` / `omr_threads`)

Khi `ENABLE_THREADS=ON` thêm linker flags: `-sUSE_PTHREADS=1 -sPTHREAD_POOL_SIZE=4`.

**Build commands:**
```bash
# Baseline (không đổi so với trước)
emcmake cmake -S . -B build -DOPENCV_VARIANT=baseline
cmake --build build

# SIMD — cần ../opencv_wasm_simd tồn tại (xem build_opencv_variants.sh)
emcmake cmake -S . -B build_simd -DOPENCV_VARIANT=simd
cmake --build build_simd

# Threads — cần ../opencv_wasm_threads tồn tại
emcmake cmake -S . -B build_threads -DOPENCV_VARIANT=threads
cmake --build build_threads
```

---

### `web/js/worker-cv.js` + `web/js/worker-yolo.js`

Thêm capability detection ở đầu mỗi file (trước `importScripts`):

```
SIMD_PROBE → WebAssembly.validate()  →  _hasSIMD
typeof SharedArrayBuffer             →  _hasThreads

_hasThreads && _hasSIMD  →  load omr_threads.js
_hasSIMD only            →  load omr_simd.js
fallback                 →  load omr.js (baseline)
```

Thêm vào `perf` object trong mỗi PROCESS_SHEET handler:
- `wasm_variant`: tên variant đang dùng (`"omr"` / `"omr_simd"` / `"omr_threads"`)
- `simd_supported`: boolean
- `threads_supported`: boolean

---

### `web/js/app.js`

1. **Bug fix**: `printBenchmarkSummary` được gọi với `Metrics.CV.stats` (array) thay vì `Metrics.CV` (object). Trước đó hàm sẽ crash khi N > 1 run vì `.map()` không tồn tại trên object.

2. **Metrics.stats**: thêm field `wasm_variant` vào mỗi entry.

3. **`printBenchmarkSummary`**: log `WASM variant: omr_simd` (hoặc variant đang dùng) ngay sau `Runs completed`.

4. **CSV export**: thêm cột `wasm_variant` vào đầu CSV (sau `method`) để dễ pivot/filter khi so sánh.

---

## Files mới tạo

### `build_opencv_variants.sh`
Script bash (WSL2/Linux) để rebuild OpenCV 4.10.0 thành hai bộ static libraries:

- `../opencv_wasm_simd/` — compile với `-msimd128`, không có pthreads
- `../opencv_wasm_threads/` — compile với `-msimd128 -pthread`

Yêu cầu: Emscripten SDK đã cài tại `$HOME/emsdk` (hoặc set biến `$EMSDK`).

```bash
bash build_opencv_variants.sh
```

### `web/server.py`
Local dev server thay thế cho `python -m http.server`. Set COEP/COOP/CORP headers trên mọi response, bắt buộc để Chrome kích hoạt `SharedArrayBuffer` (cần cho `omr_threads.wasm`).

```bash
python web/server.py        # port 8080 (mặc định)
python web/server.py 3000   # port tuỳ chọn
```

---

## Quy trình so sánh performance

1. Chạy `bash build_opencv_variants.sh` (WSL2)
2. Build 3 WASM variants (3 lệnh cmake)
3. Chạy `python web/server.py`
4. Mở `http://localhost:8080` trên Chrome
5. Upload answer key + sheet ảnh → Submit với N = 10–20 runs
6. Xem log: CV panel hiển thị `WASM variant: omr_threads` (hoặc variant được detect)
7. Export CSV → so sánh cột `cpp_omr_ms` và `e2e_ms` giữa các variant

---

## Điều kiện để mỗi variant hoạt động

| Variant | Cần gì |
|---------|--------|
| `omr` (baseline) | Luôn hoạt động |
| `omr_simd` | Chrome v91+ (WASM SIMD enabled by default) |
| `omr_threads` | Chrome v92+ **+ COEP/COOP headers** (dùng `web/server.py` hoặc Vercel) |

Vercel production (`vercel.json`) đã có đủ COEP/COOP headers, nên `omr_threads` sẽ được pick khi deploy.
