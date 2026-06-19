# HANDOFF — ĐỌC FILE NÀY TRƯỚC TIÊN

_Cập nhật: 2026-06-18. Đây là điểm vào duy nhất. Mọi file khác đều được giải thích bên dưới._

Repo này đang có **2 luồng công việc song song**. Xác định bạn đang làm luồng nào rồi
theo đúng thứ tự đọc của luồng đó.

| Luồng | Mục tiêu | Đọc theo thứ tự |
|---|---|---|
| **A. YOLO / Detection** | Cho YOLO gánh bước detect (sau khi label thêm) | `runs/diag_logs/HANDOFF_yolo_detect_experiment.md` → `ANALYSIS_dataset4_5_failure.md` |
| **B. Paper** | Sửa `omr_etc2026.tex` | Phần "Luồng B" bên dưới → các `% NOTE(...)` trong file .tex |

---

## ✅ LUỒNG A — HOÀN THÀNH (2026-06-18, B3): YOLO corner thay CV ở detect

YOLO-corner-keypoint model (full 186 ảnh, R=1.0/mAP50=.995) giờ **thay CV ở bước detect**,
chạy in-browser trên WebGPU, đạt **99.43% (9434/9488) > CV 99.00%**, mọi dataset ≥99.2%,
**179/179 sheet dùng corner (0 fallback)**. yolo_ms ~186ms (WebGPU 960), cpp ~396ms (threads).

**Phát hiện gốc:** warp từ 4 góc đúng hình học; d4/d5 từng sập 42.9% là do **Stage 2
(re-detect marker trên ảnh warp) bắt nhầm ô đen trên ảnh Zalo mờ** → đã fix bằng
**skip Stage 2 khi dùng corner-warp** (`omr_core.cpp`).

**Đã làm:** C++ `NormalizeSheetWithCorners` + `omr_process_sheet_with_corners` (binding/export,
3 variant build) + `ProcessSheetRgba(corners4, skip Stage2)`; web `bridge.processSheetWithCorners`,
worker-yolo `det=corner` (model `web/models/corner_detect.onnx` 960 WebGPU → top-4 box → 4 centres),
batch-detect `method=yolocorner`; drive_batch nhận `yolocorner`. Train: `train_corners_colab.ipynb`,
package `scripts/package_corner_dataset.py`. Validate scripts: `predict_corners_n179.py`,
`build_gt_corners.py`, `score_batch_vs_gt.py`. Artifacts: `runs/batch_results/pc_yolocorner_webgpu_{baseline,threads}_n179.json`.

**Còn lại (tùy chọn):** đo lại trên mobile (Redmi/Poco) qua adb; tối ưu `WarpRgba` (đang là vòng C++ thuần,
threads chỉ nhanh ~9%); cập nhật paper với narrative "YOLO primary detector" (Table II/§yolo-role).

---

## LUỒNG A (cũ) — bối cảnh trước B3

**Đọc trước:** [`runs/diag_logs/HANDOFF_yolo_detect_experiment.md`](runs/diag_logs/HANDOFF_yolo_detect_experiment.md)
— đây là tài liệu ĐẦY ĐỦ, tự chứa (mục tiêu, pipeline, code file:line, các thực nghiệm
đã chạy + số liệu, kết luận, ràng buộc, cách build & validate). Đọc kỹ **§4 (kết luận
cốt lõi)** và **§11 (đo perf — lật lại kỳ vọng)** trước khi viết code.

**Tóm tắt 30 giây:**
- Mục tiêu thật = perf (offload detect sang YOLO/GPU), KHÔNG phải accuracy. Accuracy đã
  99.00% và phải GIỮ.
- Đã thử nới bộ lọc marker → **sập accuracy 99→74%** → đã revert. Đừng lặp lại.
- Đo cpp_ms: detect chỉ ~2% chi phí; bottleneck là lõi CV (~90%). → Offload detect có
  **trần lợi ích rất thấp**. Cân nhắc kỹ trước khi đầu tư label + code.
- Việc đang chờ user: bổ sung nhãn (khuyến nghị keypoint 4 góc — xem §5 handoff).

**File phụ trợ của luồng A:**
- [`runs/diag_logs/ANALYSIS_dataset4_5_failure.md`](runs/diag_logs/ANALYSIS_dataset4_5_failure.md)
  — chẩn đoán chi tiết vì sao dataset_4/5 fail.
- `scripts/diag_marker_candidates.py` — port faithful bộ lọc candidate (Python sandbox).
- `scripts/diag_gate_experiment.py` — sweep chiến lược gate trên full d1–d5.
- `runs/batch_results/pc_yolo_hint20_control_n179.json` — **baseline 99.00%** (mốc đối chiếu).
- `runs/batch_results/pc_yolo_hint20_{fixed,gateonly}_n179.json` — bằng chứng regression (đừng tưởng là kết quả tốt).
- `runs/diag_logs/score_*.log`, `drive_hint20_*.log`, `build_*.log` — log thô.

---

## LUỒNG B — Paper (`omr_etc2026.tex`)

Trạng thái: đã rà soát, đã chèn **7 chỗ `% NOTE(...)`** đánh dấu việc cần làm. Số liệu
TẠM GÁC theo yêu cầu (user sẽ chèn số đã verify sau). **Không xoá** nội dung nào — chỗ
"setup bị lẫn" đang ẩn bằng `\iffalse…\fi`.

**Cách tìm việc cần làm:** mở `omr_etc2026.tex`, tìm chuỗi `% NOTE(`. Danh sách hiện tại:

| Dòng | Nhãn | Việc |
|---|---|---|
| ~64 | `handoff-numbers` | Abstract: đã bỏ số latency, chèn lại 1 bộ số Native đã thống nhất |
| ~355 | `yolo-role` | III-B: revisit nếu narrative đổi YOLO từ "candidate" → "primary detector" |
| ~360 | `setup-bleed` | III-B: 2 đoạn setup đang ẩn `\iffalse`; chuyển sang Sec IV rồi bỏ guard |
| ~404 | `N=179 vs answer keys` | Setup: nêu rõ 179 = student sheets; còn 6 đáp án (d1 có 2) cũng là sheet cần detect |
| ~436 | `table-placement` | Table I float sai vị trí |
| ~508 | `table-revisit` | Table II: hàng hard-mask gắn với narrative YOLO, sửa cùng `yolo-role` |
| ~540 | `handoff-numbers` | Mục Cross-Platform Latency: giữ số tới khi thống nhất Native cpp_ms |

**Vấn đề số liệu đã phát hiện (cần user cung cấp số chuẩn trước khi sửa):**
- "Native CV cpp_ms" xuất hiện 3 giá trị mâu thuẫn: 88 (headline 7.0×) / 106 (stage-mini) / 118 (Table IV). 615/118 = 5.2× ≠ 7.0×.
- "Native NNAPI cpp_ms = 236" chỉ có trong văn, không bảng nào có.
- "warm YOLO 142 ms" (desktop) không khớp bảng (81 hoặc 246).
- stage-mini hàng NNAPI: 158+106 = 264 = đúng Total, mâu thuẫn caption nói Total đã gồm decode+write.

**Về cách viết (đã phân tích, chưa sửa):** lặp luận điểm trung tâm ~6–7 lần; vài câu quá
dài; III-A và III-B trùng nội dung. Nên cắt ~15–20% chữ (cũng giúp giữ ≤6 trang).

---

## GIẢI THÍCH FILE MEMORY (cái bạn thấy lạ)

`C:\Users\Anh Kien\.claude\projects\...\memory\dataset4-5-marker-graygate-fail.md` và
`MEMORY.md` cùng thư mục: đây là **bộ nhớ nội bộ của Claude Code**, KHÔNG phải tài liệu
dự án. Mỗi session Claude tự động nạp `MEMORY.md` để "nhớ" các kết luận quan trọng giữa
các lần trò chuyện. **Bạn không cần đọc/sửa/quản lý chúng** — chúng chỉ là con trỏ tóm
tắt. Nguồn sự thật đầy đủ luôn là các file `.md` trong repo (HANDOFF này + trong `runs/diag_logs/`).
Nếu nội dung memory mâu thuẫn với repo, tin repo.

---

## QUYẾT ĐỊNH ĐANG CHỜ USER
1. (Luồng A) Kiểu nhãn bổ sung: keypoint 4 góc (khuyến nghị) hay box marker riêng?
2. (Luồng B) Native CV cpp_ms chuẩn là số nào (88/106/118) và 236 lấy từ đâu?
3. (Luồng B) N=179 có tính thêm 6 đáp án vào claim detection/latency không?
4. (Luồng B) Narrative YOLO cuối cùng: "localization candidate" hay "primary detector"?

## TRẠNG THÁI REPO
- `src/core/omr_warp.cpp`: đã REVERT về gốc (gate `>80.0`, area `/2`). 3 variant WASM
  (`web/wasm/omr*.wasm`) đã rebuild gốc. Accuracy = 99.00% (đã verify bằng control run).
- `omr_etc2026.tex`: có 7 `% NOTE`, 1 block ẩn `\iffalse`. Số liệu chưa đụng.
- Không còn process nền (Chrome/server đã tắt).

---

## PROMPT COPY-PASTE CHO SESSION SAU

### Nếu làm Luồng A (YOLO, sau khi đã label xong):
```
Tôi tiếp tục dự án OMR ở c:\Kien\Mobile\orm\wasm-omr-mobile. Tôi đã bổ sung nhãn YOLO
(mô tả: <điền kiểu nhãn của bạn>). Trước khi làm gì, hãy đọc theo thứ tự:
1) HANDOFF.md (gốc repo) — phần "LUỒNG A".
2) runs/diag_logs/HANDOFF_yolo_detect_experiment.md — đọc kỹ §4 và §11.
Tóm tắt lại cho tôi: mục tiêu, vì sao lần trước nới bộ lọc làm sập accuracy, và trần lợi
ích perf của việc offload detect. Sau đó đề xuất kế hoạch, KHÔNG sửa code cho tới khi tôi
duyệt. Ràng buộc bất biến: accuracy phải ≥ 99.00% (9393/9488); mọi thay đổi phải validate
full n179 chấm vs ground truth.
```

### Nếu làm Luồng B (Paper):
```
Tôi sửa paper omr_etc2026.tex ở c:\Kien\Mobile\orm\wasm-omr-mobile. Hãy đọc HANDOFF.md
(gốc repo) phần "LUỒNG B", rồi grep tất cả `% NOTE(` trong omr_etc2026.tex và liệt kê
việc cần làm. Tôi sẽ cung cấp số Native đã thống nhất. Đừng đụng số liệu cho tới khi tôi
đưa số chuẩn. Lưu ý ràng buộc: accuracy 99.00% (không bao giờ dùng lại 96.4%); target ≤6 trang.
```
