# Checklist sửa paper OMR cho Codex và Opus

File này là rule chung khi sửa `omr_etc2026_v8_4_revised.tex`. Mục tiêu là giữ paper gọn, đúng số liệu, có tính thuyết phục như một engineering benchmark nghiêm túc, không viết theo kiểu marketing.

## 1. Phạm vi bắt buộc

- Mặc định chỉ sửa `omr_etc2026_v8_4_revised.tex`.
- Không sửa file dịch, file bản cũ, code, dataset, scripts, hoặc figures nếu chưa có yêu cầu rõ.
- Nếu cần đổi figure architecture, ưu tiên sửa phần LaTeX/TikZ trong `omr_etc2026_v8_4_revised.tex`; chỉ tạo/sửa asset ngoài khi được cho phép.
- Không xóa resource usage section. Được rút gọn diễn giải, nhưng phải giữ bảng/hình và thông điệp hệ thống chạy liên tục ổn định.
- Khi sửa manuscript, ưu tiên chỉnh nội dung bên trong cấu trúc hiện có. Không đổi mạnh thứ tự section/subsection của `omr_etc2026_v8_4_revised.tex` nếu chưa có yêu cầu rõ.
- Mỗi lần sửa xong phải kiểm tra diff để chắc phần thay đổi đúng phạm vi đã chốt.

## 2. Nguyên tắc học thuật

- Không bịa metric, baseline, p-value, confidence interval, thiết bị, dataset, runtime, hoặc claim.
- Mọi claim định lượng phải bám vào bảng, hình, log, hoặc số liệu đã có trong manuscript/repo.
- Nếu một kết luận chỉ được hỗ trợ trong workload đo được, phải viết có giới hạn: `under the measured workload`, `in this benchmark`, `for the evaluated hosts`.
- Không dùng ngôn ngữ tuyệt đối hóa như `prove`, `guarantee`, `always better`, `fully solves`, `production-ready` nếu không có bằng chứng.
- Hạn chế dùng framing kiểu `limitation` nếu nó làm paper giống đang biện minh cho kết quả chưa tốt. Khi cần nêu giới hạn, viết như boundary của benchmark kèm hướng khắc phục cụ thể.
- Phần future work phải tách khỏi kết quả đã làm. Không biến future work thành claim hiện tại.

## 3. Văn phong paper

- Viết như một engineering benchmark: rõ câu hỏi, rõ setup, rõ kết quả, rõ phạm vi đo được và hướng khắc phục.
- Luôn giữ đúng định vị theo tiêu đề bài báo: đây là benchmark hệ thống/runtime/deployment, không phải algorithm paper.
- Mỗi paragraph nên trả lời một câu hỏi reviewer có thể hỏi:
  - Triển khai client-side như thế nào?
  - So sánh Web với Native có fair không?
  - Bottleneck nằm ở đâu?
  - Chạy liên tục CPU/RAM có ổn định không?
  - Kết quả này đúng trong điều kiện nào?
- Ưu tiên câu theo logic: `claim -> evidence -> interpretation -> next optimization target`.
- Giữ câu ngắn và trực tiếp. Nếu một câu có hơn hai ý, tách câu.
- Không lặp lại cùng một số liệu ở quá nhiều section; nếu cần, nhắc lại để kết nối lập luận, không kể lại bảng.
- Khi kết quả chưa tối ưu, không viết theo kiểu đổ lỗi cho thiết bị, browser, WASM, hay dataset. Viết theo hướng: kết quả chỉ ra bottleneck nào, bottleneck đó có thể được khắc phục bằng hướng kỹ thuật nào, và benchmark giúp ưu tiên hướng đó ra sao.

### Chống dấu hiệu AI-written

- Không lạm dụng câu bị động. Câu bị động vẫn dùng được khi chủ thể không quan trọng, nhưng nếu câu nào cũng có dạng `is measured`, `is shown`, `is evaluated`, `is performed`, bài sẽ đọc rất máy. Ưu tiên chủ thể rõ: `We measure`, `Table X reports`, `The Web worker dispatches`, `The benchmark separates`.
- Không viết quá nhiều câu đơn cùng nhịp. Tránh chuỗi câu kiểu `The system uses X. The model uses Y. The result shows Z.` Hãy thay đổi độ dài câu và nối ý theo logic nhân quả, đối chiếu, hoặc phạm vi đo được.
- Tránh các cụm rất giống văn AI hoặc marketing nếu không thật cần: `It is worth noting that`, `Furthermore`, `Moreover`, `Additionally` lặp dày, `plays a crucial role`, `seamless`, `robust solution`, `cutting-edge`, `state-of-the-art` không có ngữ cảnh, `delve into`, `underscore`, `pivotal`, `realm`, `revolutionize`.
- Không lạm dụng cấu trúc `not only ... but also ...`, `This highlights the importance of ...`, `This paper aims to ...` nếu câu có thể viết trực tiếp hơn.
- Tránh dấu gạch ngang dài kiểu em dash hoặc ba gạch liền để chen ý. Với manuscript, dùng dấu phẩy, dấu hai chấm, dấu chấm phẩy, ngoặc đơn, hoặc tách câu.
- Không dùng các đoạn mở đầu chung chung như `In the digital era`, `With the rapid development of technology`, `In recent years` nếu không dẫn thẳng tới gap cụ thể của benchmark.
- Mỗi paragraph sau khi rewrite phải có ít nhất một chi tiết kỹ thuật cụ thể: runtime, backend, stage, metric, device group, dataset size, hoặc bảng/hình liên quan. Nếu chỉ còn câu chung chung, phải viết lại.

Mẫu viết nên dùng:

```text
Resource traces remained bounded during the 179-sheet continuous run, indicating that the browser pipeline can sustain repeated on-device grading without progressive CPU or memory growth under the measured workload.
```

```text
These results suggest that the main end-to-end bottleneck is the WASM-executed CV stage rather than WebGPU inference.
```

```text
The measured gap identifies the WASM-executed CV stage as the main optimization target for future browser deployments.
```

```text
Compared with Native Android, the Web deployment reduces installation friction and preserves the same URL-deployed code path across devices, but it cannot call native OpenCV or NNAPI backends directly.
```

Mẫu viết cần tránh:

```text
The web app proves that browser OMR is better than native apps.
```

```text
WebGPU solves the performance problem.
```

```text
The system is guaranteed to run efficiently on all mobile devices.
```

```text
The poor result is caused by browser limitations.
```

```text
It is worth noting that the proposed system is a robust and seamless solution.
```

```text
The experiment was conducted. The latency was measured. The result was analyzed.
```

## 4. Rule theo section

### Benchmark Identity

- Paper phải đọc như một benchmark paper: câu hỏi chính là pipeline chạy thế nào trên các runtime, bottleneck nằm ở stage nào, resource có ổn định không, và deployment trade-off ra sao.
- Không viết như algorithm paper. Algorithm chỉ cần đủ để reviewer hiểu pipeline, fairness, reproducibility, và stage decomposition.
- Không overclaim đóng góp thuật toán. YOLOv8n + CV là workload/subject của benchmark; đóng góp chính là client-side Web Edge OMR benchmark, stage-level measurement, và so sánh Web/Native/server-side deployment.
- Khi mô tả detect/chấm điểm, tránh biến Methods thành phần chứng minh thuật toán mới. Tập trung vào luồng triển khai, backend, I/O, runtime isolation, và điều kiện so sánh.
- Nếu thêm hoặc sửa câu contribution, phải ưu tiên từ khóa: `pipeline-level benchmark`, `stage-level decomposition`, `Web Edge deployment`, `WASM/WebGPU runtime behavior`, `resource stability`.
- Trong chế độ raw-handoff, YOLO chỉ là localization/border-detection stage; phần C++/OpenCV quyết định recognition. Không trình bày YOLO như thành phần nâng accuracy. Artifact hiện tại cho thấy YOLO raw-handoff và CV cho cùng `9,393/9,488 (99.00%)` và cùng `137/179` sheet exact, đồng thời `154/179` detected nhưng `179/179` raw handoff với `0` fallback. Vì vậy phải nói thẳng: accuracy bằng nhau là bằng chứng robustness của raw handoff, không phải YOLO làm tốt hơn CV. Nếu giữ claim "YOLO+CV", phải định lượng được bao nhiêu sheet output detector thực sự đổi kết quả so với CV thuần.

### Ràng buộc từ bài tham khảo

- Các paper OMR nên dùng để học cách viết workload thực tế: nêu rõ input condition, sheet layout, dataset size, accuracy/error metric, processing time, và error distribution. Không học theo hướng biến bài thành thuật toán mới.
- Các paper benchmark WebAssembly/browser inference nên dùng để học cấu trúc lập luận: đặt research gap, mô tả controlled setup, đo stage-level latency/resource, rồi chuyển gap đo được thành optimization target.
- Không mang số liệu từ paper khác vào claim của paper mình nếu không cite trực tiếp và không thật sự cần. Bài tham khảo chỉ là chuẩn cách viết, không phải nguồn thay thế cho kết quả của mình.
- Gap framing nên giữ hướng này: existing OMR work tập trung vào recognition method/mobile or server system; browser benchmark work tập trung vào WASM/DL inference nói chung; thiếu benchmark pipeline-level cho OMR chạy client-side với WASM CV + WebGPU YOLO + Native Android baseline.
- Câu kết quả chưa tối ưu phải viết như benchmark insight:

```text
The measured gap identifies the WASM-executed CV stage as the main optimization target, rather than invalidating browser-side OMR deployment.
```

```text
The benchmark separates model inference from deterministic CV processing, allowing the deployment bottleneck to be attributed at the pipeline level.
```

### Related Work

- Rút gọn thành phần cần thiết để dẫn tới research gap.
- Không viết như một survey dài về OMR, YOLO, WebAssembly, WebGPU.
- Phải giữ được mạch: classical OMR có giới hạn -> YOLO tăng robustness -> browser edge có lợi ích privacy/latency nhưng có runtime overhead -> chưa có benchmark pipeline-level stage-decomposed.

### System Architecture and Methods

- Nên gom thành 3 cụm lập luận chính:
  1. Luồng client-side Web trên desktop/mobile: camera/upload, canvas, worker, WASM CV, ONNX Runtime Web, WebGPU YOLO.
  2. Thuật toán detect và chấm điểm: CV-only, YOLO+CV, homography, thresholding, fixed grid, bubble scoring, suspicious/multi-mark flags.
  3. So sánh Web, Native Android, và server-side: kiến trúc, fairness, lợi ích, bất lợi.
- Reviewer phải thấy rõ Web và Native compare fair ở đâu:
  - cùng ONNX model;
  - cùng evaluation set;
  - cùng deterministic OMR logic về mặt chức năng;
  - khác backend/runtime là đối tượng cần benchmark, không phải confound cần che giấu.
- Khi nói Native Android, phải nêu rõ nó dùng OpenCV Android/JNI và ONNX Runtime Android CPU/NNAPI.
- Khi nói Web, phải nêu rõ CV chạy qua WASM/OpenCV.js, YOLO chạy qua ONNX Runtime Web/WebGPU nếu backend hỗ trợ.
- Khi nói server-side, chỉ dùng làm deployment contrast: server có compute tập trung nhưng phát sinh network round-trip và privacy exposure.

### Architecture Figure

- Chuyển figure architecture về 1 cột, không để `figure*` bung hai cột nếu đang sửa section này.
- Caption phải nói rõ:
  - WebGPU chỉ tăng tốc YOLO/localization;
  - WASM C++/OpenCV xử lý normalization, thresholding, bubble reading, grading;
  - main thread/UI và worker được tách để giữ responsiveness.
- Hình không được quá dày chữ. Nếu cần, rút label trong node, đưa giải thích vào caption/text.

### Experimental Setup

- Rút gọn setup, nhưng phải giữ các thông tin reviewer cần để tin benchmark:
  - 7 host configurations;
  - Web Edge vs Native Android;
  - dataset 179 sheets và 100-sheet labelled subset nếu đang nói accuracy;
  - metrics: accuracy, latency, stage decomposition, resource usage;
  - fairness controls: same exported ONNX model, same input set, same benchmark protocol where applicable.
- Không lặp lại chi tiết đã có trong Methods nếu không cần cho reproducibility.

### Results and Discussion

- Giữ structure theo `experimental_results_discussion.tex`: Recognition Accuracy -> Cross-Platform Latency -> System Resource Usage. Nếu bản main đang có thêm phần SIMD/WASM overhead, giữ nó gần latency và không để nó phá mạch chính. Lưu ý: `experimental_results_discussion.tex` chỉ dùng làm dàn ý/thứ tự section, KHÔNG lấy số liệu từ đó (file này còn chứa số legacy `96.4%`). Mọi số liệu lấy từ `omr_etc2026_v8_4_revised.tex` và `runs/`.
- Giữ trọng tâm lập luận: accuracy tương đương, latency khác nhau do runtime/stage bottleneck, resource bounded trong continuous run.
- Khi kết quả cho thấy Web chậm hơn Native, không diễn giải như thất bại của hệ thống. Diễn giải là benchmark đã định vị đúng optimization target: WASM CV stage, memory model, threading/SIMD path, hoặc browser-accessible CV acceleration.
- Khi so sánh CPU/RAM, phải nhớ instrument khác nhau:
  - psutil renderer-process CPU% trên desktop/laptop Chrome;
  - in-page event-loop proxy trên Safari/Android Chrome;
  - adb top trên Native Android.
- Không so sánh CPU% trực tiếp giữa các instrument nếu không có câu giới hạn. Chỉ nên nói trong từng nhóm hoặc nói qualitative có điều kiện.
- Resource usage không cắt. Rút gọn bằng cách gom diễn giải:
  - CPU remained bounded;
  - RAM remained within device capacity;
  - no progressive growth during 179-sheet run;
  - Native có throughput tốt hơn nhưng Web có deployment/privacy/install trade-off.

### Conclusion và Future Work

- Conclusion ngắn, nhấn vào 2-3 kết luận chính:
  - browser OMR khả thi về accuracy và deployment;
  - bottleneck chính là WASM CV stage, không phải YOLO inference khi WebGPU đã bật;
  - resource usage trong workload đo được ổn định/within bounded range.
- Future work chỉ 1-2 câu. Không dùng danh sách dài.
- Future work nên viết như hướng khắc phục trực tiếp từ benchmark, không phải danh sách limitation. Ưu tiên:
  - optimize WASM/OpenCV path hoặc WebNN/TFLite-WASM alternatives;
  - energy/power measurement hoặc broader device validation nếu cần.

## 5. Rule so sánh Web, Native, Server

- Không viết `Web is better than Native` theo nghĩa chung.
- Viết đúng trade-off:
  - Web: URL deployment, no app-store install, cross-device code path, on-device privacy; bất lợi là browser sandbox, WASM memory model, hạn chế native GPU/CV backend.
  - Native Android: latency/resource predictability tốt hơn, truy cập JNI/OpenCV/NNAPI; bất lợi là cài đặt, packaging, platform-specific maintenance.
  - Server-side: compute tập trung và dễ quản lý model; bất lợi là upload latency, connectivity dependence, privacy exposure với answer sheets.
- Khi kết luận, nói theo ngữ cảnh:

```text
Native Android remains the lower-latency deployment, while Web Edge offers a privacy-preserving and installation-free alternative whose feasibility depends on keeping the WASM CV stage bounded.
```

## 6. Rule giữ structure manuscript

- `experimental_results_discussion.tex` là khung tham chiếu cho Results/Discussion: giữ thứ tự Accuracy -> Latency -> Resource Usage khi cập nhật `omr_etc2026_v8_4_revised.tex`. Chỉ dùng làm dàn ý/sườn, KHÔNG phải nguồn số liệu — số `96.4%` trong file đó là legacy và không được kéo vào manuscript.
- Không đổi tên hàng loạt section/subsection, không tách nhập section lớn, không di chuyển bảng/hình lớn nếu mục tiêu chỉ là rút gọn và nâng văn phong.
- Có thể rút gọn Related Work, Setup, Methods, Conclusion, Future Work theo góp ý co-author, nhưng nên làm bằng cách rewrite paragraph trong khung hiện tại.
- Methods có thể được nén về 3 cụm ý như co-author đề xuất, nhưng không cần phá toàn bộ heading nếu giữ heading hiện tại giúp reviewer theo dõi tốt hơn.
- Nếu cần thay đổi structure lớn, phải có lý do cụ thể: figure architecture không vừa 1 cột, duplicated subsection, hoặc Results đang đi sai mạch benchmark.

## 7. Từ vựng nên dùng và nên tránh

Nên dùng:

- `indicate`
- `suggest`
- `show`
- `remain bounded`
- `under the measured workload`
- `stage-level decomposition`
- `pipeline-level benchmark`
- `deployment trade-off`
- `runtime bottleneck`
- `browser sandbox`
- `installation friction`
- `on-device privacy`

Nên tránh:

- `prove`
- `guarantee`
- `always`
- `perfect`
- `superior to native`
- `real-time on all devices`
- `production-ready`
- `solves the problem`
- `It is worth noting that`
- `plays a crucial role`
- `seamless`
- `cutting-edge`
- `revolutionize`
- `delve into`
- `underscore`
- `pivotal`
- `realm`
- `robust solution`

## 8. Checklist trước khi kết thúc một lượt sửa

- Diff chỉ chạm file được phép sửa.
- Không có metric mới nếu chưa có nguồn.
- Related Work và Setup được rút gọn, không mất research gap/fairness.
- Methods nói rõ client-side architecture trên desktop/mobile và Native Android baseline.
- Results/Discussion vẫn giữ mạch theo `experimental_results_discussion.tex` (chỉ về thứ tự section, không lấy số liệu).
- Resource usage vẫn còn và vẫn chứng minh continuous-run stability.
- Không còn dấu hiệu AI-written rõ ràng: câu bị động dày đặc, transition lặp, câu đơn đều đều, cụm marketing, hoặc dấu gạch ngang dài kiểu em dash.
- Architecture figure nếu được sửa thì ở 1 cột, không tràn cột, caption đúng vai trò WebGPU/WASM.
- Conclusion ngắn; future work 1-2 câu.
- Compile LaTeX nếu có sửa manuscript.
- Nếu compile fail, ghi rõ lỗi còn lại và file/line liên quan.
- Nếu tạo artifact mới trong `runs/`, cập nhật `runs/RUN_LOG.md` và README của folder liên quan để phân biệt canonical/rerun/legacy.
- Nếu chuyển file vào `legacy/` hoặc `archived/`, folder đó phải có README nói rõ vì sao file không còn là source of truth.

## 9. Cách Codex và Opus phối hợp

- Codex khi sửa: ưu tiên diff gọn, giữ claim đúng số liệu, compile sau khi sửa.
- Opus khi review: ưu tiên bắt bug lập luận, claim quá đà, comparison chưa fair, section quá dài, figure/caption gây hiểu nhầm.
- Mỗi góp ý phải gắn vào một section hoặc một claim cụ thể. Tránh góp ý chung chung như "viết hay hơn".
- Nếu có xung đột giữa văn phong hay và độ chính xác, chọn độ chính xác.

## 10. Active backlog (bản ≤6 trang)

Mục tiêu hiện tại: checklist này chỉ giữ việc còn phải làm cho bản 5–6 trang hiện tại. `omr_etc2026_v8_4_revised.tex` là source of truth duy nhất; bản compile mới nhất (2026-05-30) ra **5 trang**, 0 overfull, 0 undefined ref, 7 underfull. Trần cho phép là **6 trang**. Các bug cũ của bản 13 trang đã bị xóa khỏi active backlog nếu manuscript hiện tại không còn bảng, hình, hoặc claim đó.

Cảnh báo legacy: không lấy số liệu từ `omr_etc2026.tex` (bản manuscript cũ), `experimental_results_discussion.tex`, bản dịch, `docs/AGENT_CONTEXT_OMR_PROJECT.md`, hay các handoff doc — những file đó còn chứa số legacy `96.4%`. Accuracy chính thức hiện tại là `99.00%` (`9,393/9,488`) có ground-truth review thật trong `runs/accuracy_eval/`. `96.4%` đã bị loại khỏi `omr_etc2026_v8_4_revised.tex`; đừng để nó quay lại.

### P0 - Hardening thuc nghiem neu muon ban gan "perfect"

- [x] **Verify YOLO deployment border detection tren 179-sheet run.**
  - Nguon can kiem: `runs/batch_results/redmi_note13_pro_plus_yolo_raw_n179.json`.
  - Viec can lam: aggregate `yolo_detected`, `yolo_fallback`, `yolo_chosen_path`, va `yolo_fallback_used`. Neu ket qua la `179/179` detected va fallback `none`, report rieng nhu system-level border detection evidence.
  - Luu y: tach claim nay khoi downstream hard-mask recognition. Border detection tot khong dong nghia crop/mask handoff se cho accuracy tot.
  - Da lam 2026-05-28: tao `runs/accuracy_eval/yolo_detection_n179/summary.json` va `per_sheet_detection.csv` bang `scripts/summarize_yolo_detection.py`. Ket qua hien tai la `154/179` `yolo_detected=true`, `179/179` raw handoff, `0` fallback used; khong duoc claim `179/179` detector success tu artifact nay.
  - Rerun 2026-05-29 qua Redmi USB/CDP: `runs/accuracy_eval/yolo_detection_20260529_n179/summary.json` lap lai ket qua `154/179` detected, `179/179` raw handoff, `0` fallback.

- [~] **Rerun Redmi Web CV và YOLO raw trên 179-sheet artifact — cần ≥3 session trước khi chốt canonical.**
  - Đã làm 1 session 2026-05-29 với Redmi Note 13 Pro+ qua USB debugging, ADB reverse port 8080, và Chrome CDP port 9222.
  - Artifacts: `runs/batch_results/redmi_note13_pro_plus_20260529_cv_n179.json`, `runs/batch_results/redmi_note13_pro_plus_20260529_yolo_raw_n179.json`, resource CSVs trong `runs/resources/redmi_note13_pro_plus_20260529/`.
  - Summary 1 session: CV `cpp_ms=795.2 +/- 118.7` ms; YOLO raw `cpp_ms=820.1 +/- 75.5` ms, `yolo_ms=309.4 +/- 39.0` ms, `worker_total_ms=1130.1 +/- 89.0` ms.
  - Chính sách chốt 2026-05-30: YOLO raw rerun chênh ~27% so với artifact paper gốc (`cpp_ms` 820 vs 648) trong khi CV sát (795 vs 808). Không được thay số bằng 1 lần chạy. Phải chạy **≥3 session độc lập** (để máy nguội giữa các lần, ghi kèm điều kiện nhiệt/tần số qua `FreqMonitor`), rồi report mean±SD across sessions và mới chọn canonical. Mục tiêu: biến variance thành bằng chứng đo lặp thay vì điểm yếu reviewer bắt được.

- [~] **Add Native per-question export cho CV và YOLO variants — export ĐÃ LÀM 2026-05-30; recognition còn diverge.**
  - Đính chính: blocker "workspace thiếu Android source/build" trong các doc trước là sai. Project Android đầy đủ và build được nằm ở `C:/Kien/Mobile/orm/android` (đủ `gradlew.bat`, `build.gradle.kts`, `settings.gradle.kts`, keystore, 31 file Kotlin gồm `HeadlessBenchmark.kt`, `CvBenchmarkRunner.kt`, `YoloBenchmarkRunner.kt`, `OmrProcessor.kt`).
  - Sửa lệnh build stale: project **không có product flavor**; chỉ `assembleDebug`/`assembleRelease`, CV vs YOLO chọn lúc runtime qua `--es method cv|yolo`. Lệnh `assembleTraditionalDebug assembleYoloDebug` ở các doc cũ là sai.
  - Đã làm 2026-05-30: serialize `auto_q01..auto_q60` (+ stage timers) trong `SheetCapture`/`HeadlessBenchmark.writeJson`; build debug; chạy headless 179 cho CV và YOLO tri-provider trên Redmi. Artifacts: `runs/batch_results/redmi_note13_pro_plus_native_cv_n179.json`, `redmi_note13_pro_plus_native_yolo_{cpu1,cpu4,nnapi}_n179.json`. So sánh bằng `scripts/compare_native_web.py`. Chi tiết trong `runs/RUN_LOG.md` (2026-05-30).
  - PHÁT HIỆN + FIX (3 tầng, xem `runs/RUN_LOG.md` 2026-05-30/05-31): (1) `NormalizePaper.kt` từng là port stale của `src/core/omr_warp.cpp` → re-port (800px downscale, siết filter marker, `CornersLookValid`). (2) Filter marker `mean_gray<80` quá chặt cho ảnh Redmi nền tối (marker gray~90) → nới `<130`; warp giờ đúng cả dataset_4/5. (3) Bubble reader: re-port nguyên cụm C++ "Rule E" (`adaptiveThreshold(31,5)` + circular density + global_avg normalization + per-row z-score) vào `BubbleDetector`/`OmrProcessor`. **Đo vs GROUND TRUTH (NA excluded, `compare_native_web.py --gt-only`): dataset_1 99.6%, dataset_2 99.8%, dataset_3 99.9% = PARITY với Web; overall 87.1%; sheet-exact 138/179 (Web/paper 137/179).** Bẫy metric: KHÔNG dùng vs-Web mode (đếm cả `auto_q51..60` ở dataset 50-câu nơi `gt=NA` → noise, ra ~79% gây hiểu nhầm). CÒN LẠI: 25 sheet nền tối (21 dataset_4 + 4 dataset_5) corner detection chọn nhầm marker → warp méo (24 sheet dataset_4 warp đúng đọc >90% → bubble-reader đúng, chỉ thiếu localization). Đã thử quadrant-farthest / sum-diff extremes / all-candidate — đều ~65% dataset_4; cần khớp C++ marker detection chính xác hoặc YOLO localize. (4) Native YOLO runner dùng `YoloPaperDetector.normalize` (crop/warp bbox) làm OMR normalizer — KHÁC Web raw-handoff → recognition YOLO vỡ; muốn match Web phải đưa ảnh raw vào CV normalize sau YOLO localize.
  - Timing thu được là **debug build** (không canonical); release sẽ đổi OMR/CV Kotlin time. Để có latency canonical phải build release.
  - Lý do: chỉ sau khi recognition parity được xác nhận (dataset_4/5 fix + Native YOLO raw-handoff + GT labels) Native Android mới được nâng từ latency/resource baseline lên recognition baseline. Trước đó, Methods/Threats/Conclusion vẫn phải scope Native là latency/resource baseline.

- [ ] **Rerun stage decomposition tren 179-sheet artifact hoac giu `N=43` la stage profile ro rang.**
  - Trang thai hien tai: paper da label `N=43` trong `tab:stage-mini`, nhung do van la profile khac main deployment set.
  - Viec can lam neu co thoi gian: log Mobile Web stage timers tren du 179 sheets de bien `13.5x` thanh same-artifact attribution, khong chi profile-derived attribution.
  - Partial 2026-05-29: Web raw rerun da co `yolo_ms`, `cpp_ms`, va `worker_total_ms` tren 179 sheets, nhung chua co finer decode/post-processing/browser wall-time fields va chua co Native same-boundary rerun.

- [ ] **Collect same-boundary latency traces Web/Native.**
  - Viec can lam: ca Web va Native cung log decode, detector/ORT, OMR/CV, post-processing/debug write, va end-to-end total.
  - Ly do: giup tat ca ratio Web-vs-Native dung cung boundary, giam nhu cau giai thich `cpp_ms` vs `e2e_ms`.
  - Partial 2026-05-29: Redmi Web CV/YOLO traces đã thu lại. Native same-boundary traces nay đã unblock (project ở `C:/Kien/Mobile/orm/android`); cần thêm component timers (decode/ORT/OMR/debug-write/E2E) vào Native runner cùng lúc với per-question export rồi rerun.

- [x] **Lập platform/resource manifest duy nhất.**
  - Việc cần làm: ghi rõ host, OS/browser/runtime version, instrument CPU/RAM, artifact path, và aggregation policy cho từng bảng/hình resource.
  - Lý do: resource table hiện đã ghi instrument-specific units, nhưng bản perfect nên có manifest tái tạo được.
  - Đã làm 2026-05-28: tạo `docs/ARTIFACT_MANIFEST_5PAGE.md`. Stage profile vẫn được label là profile-derived cho đến khi rerun 179-sheet.

### P0.5 - Đề xuất cải thiện/mở rộng thực nghiệm (thêm 2026-05-30)

Danh sách đề xuất để bản benchmark thuyết phục hơn với reviewer. Ưu tiên cái rẻ-tác-động-cao trước; chỉ làm khi có thời gian và không làm vỡ trần 6 trang.

1. **Multi-session variance cho host latency, không chỉ Redmi.** Hiện mỗi host chỉ 1 lần chạy. Chạy ≥3 session cho ít nhất 2 host đại diện (desktop reference + Redmi mobile) để có mean±SD across sessions. Trả lời thẳng câu reviewer "số này có ổn định không".
2. **Native recognition parity.** Sau khi có per-question export (P0), so Native CV/YOLO với cùng GT: hoặc khẳng định parity với Web `99.00%`, hoặc định vị divergence. Đây là mảnh fairness lớn nhất đang thiếu.
3. **Định lượng vai trò YOLO trong raw-handoff.** Accuracy YOLO == CV. Báo cáo riêng: bao nhiêu sheet output detector thực sự đổi kết quả so với CV thuần, và 25 sheet không `yolo_detected` có ảnh hưởng accuracy không. Tránh để YOLO trông như decorative.
4. **Browser wall-time / E2E thật cho Web.** Hiện chỉ có worker `cpp_ms`. Thêm decode + main-thread + post-processing để có một cột E2E Web so cùng boundary với Native `e2e_ms`, giảm gánh nặng giải thích `cpp_ms` vs `e2e_ms`.
5. **Thermal/throttling control.** Ghi nhiệt độ/tần số khi chạy (Native đã có `FreqMonitor.kt`) để giải thích variance giữa các session, nhất là trên mobile.
6. **Energy/power measurement (optional, future work).** Chỉ làm nếu venue yêu cầu; giữ ở future work, không biến thành claim hiện tại.

### P1 - Polish claim và manuscript theo evidence mới

- [x] **Reframe fixed-template as workload scope, not paper weakness.**
  - Paper dang nghien cuu fixed-template OMR by design. Chi goi arbitrary-template/general-layout support la future work neu paper muon mo rong ngoai workload nay.
  - Da lam 2026-05-28: Threats trong manuscript da doi thanh intended workload scope, khong phai claim arbitrary-layout OMR.

- [x] **Keep Native scope consistent until Native recognition export exists.**
  - Neu chua lam P0 Native export, Methods/Threats/Conclusion phai tiep tuc noi Native Android la latency/resource baseline.
  - Da kiem 2026-05-28: Methods va Threats van scope Native Android la latency/resource baseline.

- [x] **Neu giu Welch tests, ghi metric boundary ngay tai cau chua test.**
  - Test same-boundary `cpp_ms` co the giu, nhung khong de reviewer doc thanh t-test tren table E2E Native rows.
  - Da kiem 2026-05-28: cau Welch ghi ro same-boundary `cpp_ms`.

- [x] **Neu them claim YOLO 100% border detection, them artifact path hoac table note.**
  - Khong chi viet trong prose; can cho reviewer biet claim duoc aggregate tu JSON nao.
  - Da lam 2026-05-28: khong them claim `100%` detector success vi artifact chi co `154/179`; manuscript them artifact path cho raw-handoff audit.

- [x] **Kiem tra lai wording ve fixed-template trong Threats.**
  - Muc tieu: noi ro benchmark scope la fixed-template; khong viet nhu paper bi loi vi khong xu ly arbitrary template.
  - Da lam 2026-05-28.

### P2 - Camera-ready hygiene

- [ ] **Compile final PDF sau moi lan sua `.tex`.**
  - Check page count, undefined refs/cites, va `Overfull \hbox`.

- [ ] **Verify references truoc submission.**
  - Kiem tra author initials, venue, URL/DOI, va access-style cho web/software references.

- [ ] **Dung vector figures neu da co san va compile on dinh.**
  - Current paper dang dung PDF figures cho latency/resource; giu policy nay khi regenerate figures.

- [ ] **Chi regenerate translation/sidecar sau khi canonical `.tex` da freeze.**
  - `omr_etc2026_v8_4_revised.tex` la source of truth hien tai.

## 11. Quy tac kiem chung truoc khi sua Results tiep

- [ ] Moi bang/hinh trong Results phai co artifact path ro rang trong `runs/` hoac `figures/` va script tai tao.
- [ ] Moi claim accuracy phai co ground truth artifact; khong dung diagnostic proxy thay cho accuracy neu paper dang noi accuracy.
- [ ] Moi so latency phai ghi metric: `cpp_ms`, `ort_ms`, `e2e_ms`, browser wall-time, hay stage-level time.
- [ ] Moi so resource phai ghi instrument: psutil, browser proxy, hay Android native sampler.
- [ ] Moi so Web-vs-Native phai noi dung context: Web worker compute, Native file-based E2E, streaming camera E2E, hay stage profile.
- [ ] Neu dung so cu/legacy thi van duoc, nhung phai label la legacy/secondary/stage-profile; khong de chung voi deployment `N=179` nhu ket qua chinh.
- [ ] Sau khi sua bang/hinh/claim, compile lai va check page count, undefined refs/cites, overfull/underfull warnings.
