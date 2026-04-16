# GradeSnap Android

Ứng dụng chấm thi trắc nghiệm tự động bằng Camera / Gallery.

## Tech Stack
- **Kotlin** + Android SDK (API 24+)
- **OpenCV 4.9** (via Maven wrapper `com.quickbirdstudios:opencv`)
- **CameraX** – chụp ảnh
- **Coroutines** – xử lý ảnh nền
- **ViewBinding** + RecyclerView

---

## Cấu trúc project

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
│   ├── libs.versions.toml       ← version catalog
│   └── wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   └── omr_layout_config.json   ← layout phiếu thi
        ├── java/com/gradesnap/omr/
        │   ├── MainActivity.kt          ← Camera + Gallery entry point
        │   ├── ResultActivity.kt        ← Hiển thị kết quả
        │   ├── AnswerAdapter.kt         ← RecyclerView adapter
        │   ├── NormalizePaper.kt        ← Warp perspective (port omr_normalize.py)
        │   ├── BubbleDetector.kt        ← Bubble detection (port omr_flexible.py)
        │   ├── ConfigLoader.kt          ← Đọc JSON config
        │   └── OmrProcessor.kt          ← Orchestrator tổng
        └── res/
            ├── layout/
            │   ├── activity_main.xml
            │   ├── activity_result.xml
            │   └── item_answer.xml
            ├── values/
            │   ├── strings.xml
            │   ├── colors.xml
            │   └── themes.xml
            └── xml/file_paths.xml       ← FileProvider config
```

---

## Cách build

### 1. Mở project trong Android Studio
```
File → Open → chọn thư mục android/
```

### 2. Sync Gradle
Android Studio sẽ tự download:
- OpenCV 4.9 từ Maven
- CameraX, Coroutines, Material 3

### 3. Run (API 24+)
Kết nối thiết bị hoặc tạo AVD → nhấn ▶

---

## Flow xử lý

```
Bitmap (Camera/Gallery)
    ↓
Utils.bitmapToMat()
    ↓
NormalizePaper.normalize()
    ├── findMarkerCorners()    ← tìm 4 dấu vuông ở góc
    ├── orderPoints()          ← sắp xếp TL, TR, BR, BL
    └── warpPerspective()      ← chuẩn hóa → 1700×2400 px
    ↓
BubbleDetector.preprocess()   ← GaussianBlur + Otsu THRESH_INV
    ↓
ConfigLoader.load()            ← đọc omr_layout_config.json
    ↓
BubbleDetector.findFilledOption() × 60 câu
    ├── countNonZero() per cell    ← thay np.sum(region > 0)
    ├── max white_ratio ≥ 15%
    └── gap với second_max ≥ 3%
    ↓
Map<Int, String?>  {1→"A", 2→"C", ...}
    ↓
ResultActivity (RecyclerView)
```

---

## Mapping Python → Kotlin

| Python (NumPy/OpenCV) | Kotlin (OpenCV Java) |
|---|---|
| `np.sum(region > 0)` | `Core.countNonZero(mat)` |
| `np.argmin(arr)` | `arr.indexOf(arr.min())` |
| `np.array(pts, dtype=float32)` | `MatOfPoint2f(*pts)` |
| `cv2.imread()` | `BitmapFactory + Utils.bitmapToMat()` |
| `cv2.warpPerspective()` | `Imgproc.warpPerspective()` |
| `cv2.threshold()` | `Imgproc.threshold()` |
| `cv2.GaussianBlur()` | `Imgproc.GaussianBlur()` |
| `cv2.findContours()` | `Imgproc.findContours()` |

---

## Thêm OpenCV SDK thủ công (nếu cần)

Nếu muốn dùng SDK 4.12.0 (đã có `opencv-4.12.0-android-sdk.zip`):

1. Extract zip ra thư mục cùng cấp với `android/`
2. Bỏ comment trong `settings.gradle.kts`:
   ```kotlin
   include(":opencv")
   project(":opencv").projectDir = File("../opencv-4.12.0-android-sdk/sdk")
   ```
3. Trong `app/build.gradle.kts`, thay:
   ```kotlin
   // implementation("com.quickbirdstudios:opencv:4.9.0")
   implementation(project(":opencv"))
   ```
