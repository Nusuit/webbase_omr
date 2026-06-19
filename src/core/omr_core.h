#pragma once

#include <array>
#include <cstdint>

namespace omr {

struct Roi {
  int x = 0;
  int y = 0;
  int w = 0;
  int h = 0;
};

struct FrameProcessResult {
  int status = 0;
  int black_count = 0;
  int width = 0;
  int height = 0;
  Roi roi{};
};

struct SheetProcessResult {
  int status = 0;
  int mssv_valid = 0;
  std::array<int, 6> mssv_digits{{-1, -1, -1, -1, -1, -1}};
  int key_valid = 0;
  std::array<int, 3> key_digits{{-1, -1, -1}};
  std::array<int, 60> answer_masks{};
  std::array<int, 60> suspicious{};
  std::array<int, 300> bubble_densities{};
};

class OmrCore {
 public:
  FrameProcessResult ProcessRgbaFrame(std::uint8_t* rgba, int width, int height, const Roi& roi) const;
  // `corners4`, when non-null, points to 8 doubles (x0,y0..x3,y3) giving the
  // four corner-marker centres in source-image pixels from a YOLO keypoint
  // detector. Stage 1 then warps directly from these points (no blob
  // detection); it falls back to marker_hint / blob detection only if the
  // points are degenerate. `marker_hint` is the older bbox-guided path.
  SheetProcessResult ProcessSheetRgba(std::uint8_t* rgba, int width, int height,
                                      const Roi* marker_hint = nullptr,
                                      const double* corners4 = nullptr) const;
  // Copies the 1700×2400 BINARY_INV preview produced by the last ProcessSheetRgba
  // call into dst (must have dst_len >= 1700*2400*4 bytes).
  // Returns bytes written, or -1 on error.
  int CopyLastPreview(std::uint8_t* dst, int dst_len) const;
  // Copies the 1700×2400 COLOR-WARPED RGBA image (before binarization) from the
  // last ProcessSheetRgba call. Used for the annotated preview overlay.
  int CopyLastWarped(std::uint8_t* dst, int dst_len) const;
  void ClearPreviews();

 private:
  static Roi ClampRoi(int width, int height, const Roi& roi);
};

}  // namespace omr
