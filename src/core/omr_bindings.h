#pragma once

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

struct OmrRoi {
  int x;
  int y;
  int w;
  int h;
};

struct OmrFrameResult {
  int status;
  int black_count;
  int width;
  int height;
  OmrRoi roi;
};

int omr_process_frame(std::uint8_t* rgba, int width, int height, int roi_x, int roi_y, int roi_w, int roi_h);
OmrFrameResult omr_process_frame_v2(
    std::uint8_t* rgba,
    int width,
    int height,
    int roi_x,
    int roi_y,
    int roi_w,
    int roi_h);

// out_values format (length >= 132):
// [0] status
// [1] mssv_valid
// [2..7] mssv digits (6)
// [8] key_valid
// [9..11] key digits (3)
// [12..71] answer_masks for q1..q60 (5-bit mask A..E)
// [72..131] suspicious flags for q1..q60
int omr_process_sheet(std::uint8_t* rgba, int width, int height, int* out_values, int out_len);

// After omr_process_sheet, copies the internal 1700×2400 normalized binary
// image (BINARY_INV: paper=0, filled=255) into dst.
// dst must be pre-allocated with at least 1700*2400*4 bytes.
// Returns the number of bytes written, or -1 on error.
int omr_get_last_preview(std::uint8_t* dst, int dst_len);

// After omr_process_sheet, copies the 1700×2400 COLOR RGBA warped image
// (before binarization) into dst for use in annotated preview overlays.
// dst must be pre-allocated with at least 1700*2400*4 bytes.
// Returns the number of bytes written, or -1 on error.
int omr_get_last_warped(std::uint8_t* dst, int dst_len);

#ifdef __cplusplus
}
#endif
