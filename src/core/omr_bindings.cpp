#include "core/omr_bindings.h"

#include "core/omr_core.h"

namespace {
omr::OmrCore g_core;
}

extern "C" {

int omr_process_frame(std::uint8_t* rgba, int width, int height, int roi_x, int roi_y, int roi_w, int roi_h) {
  const omr::Roi roi{roi_x, roi_y, roi_w, roi_h};
  const omr::FrameProcessResult result = g_core.ProcessRgbaFrame(rgba, width, height, roi);
  if (result.status != 0) {
    return result.status;
  }
  return result.black_count;
}

OmrFrameResult omr_process_frame_v2(
    std::uint8_t* rgba,
    int width,
    int height,
    int roi_x,
    int roi_y,
    int roi_w,
    int roi_h) {
  const omr::Roi roi{roi_x, roi_y, roi_w, roi_h};
  const omr::FrameProcessResult core_result = g_core.ProcessRgbaFrame(rgba, width, height, roi);

  OmrFrameResult out;
  out.status = core_result.status;
  out.black_count = core_result.black_count;
  out.width = core_result.width;
  out.height = core_result.height;
  out.roi = OmrRoi{core_result.roi.x, core_result.roi.y, core_result.roi.w, core_result.roi.h};
  return out;
}

int omr_process_sheet(std::uint8_t* rgba, int width, int height, int* out_values, int out_len) {
  if (!out_values || out_len < 132) {
    return -2;
  }

  const omr::SheetProcessResult result = g_core.ProcessSheetRgba(rgba, width, height);

  out_values[0] = result.status;
  out_values[1] = result.mssv_valid;

  for (int i = 0; i < 6; ++i) {
    out_values[2 + i] = result.mssv_digits[i];
  }

  out_values[8] = result.key_valid;
  for (int i = 0; i < 3; ++i) {
    out_values[9 + i] = result.key_digits[i];
  }

  for (int i = 0; i < 60; ++i) {
    out_values[12 + i] = result.answer_masks[i];
    out_values[72 + i] = result.suspicious[i];
  }

  return result.status;
}

int omr_get_last_preview(std::uint8_t* dst, int dst_len) {
  return g_core.CopyLastPreview(dst, dst_len);
}

int omr_get_last_warped(std::uint8_t* dst, int dst_len) {
  return g_core.CopyLastWarped(dst, dst_len);
}

}
