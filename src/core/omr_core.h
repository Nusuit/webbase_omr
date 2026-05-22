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
  SheetProcessResult ProcessSheetRgba(std::uint8_t* rgba, int width, int height) const;
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
