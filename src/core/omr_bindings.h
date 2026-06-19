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

int omr_process_sheet_with_hint(
    std::uint8_t* rgba,
    int width,
    int height,
    int hint_x1,
    int hint_y1,
    int hint_x2,
    int hint_y2,
    int* out_values,
    int out_len);

// YOLO corner-keypoint path: (x0,y0)..(x3,y3) are the four corner-marker
// centres in source-image pixels, in any order. The core sorts them
// TL/TR/BR/BL and warps directly, skipping blob detection.
int omr_process_sheet_with_corners(
    std::uint8_t* rgba,
    int width,
    int height,
    double x0, double y0,
    double x1, double y1,
    double x2, double y2,
    double x3, double y3,
    int* out_values,
    int out_len);

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
