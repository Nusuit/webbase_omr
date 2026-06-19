#pragma once

#include <cstdint>

namespace omr {

// Attempts to detect the 4 corner markers of the answer sheet within `src_rgba`
// (arbitrary size) and warp the sheet to a canonical 1700×2400 RGBA image
// written into `dst_rgba` (must be pre-allocated as 1700 * 2400 * 4 bytes).
//
// Returns true  → perspective-corrected sheet is in dst_rgba.
// Returns false → could not detect corners reliably; caller should fall back to
//                 a simple resize.
bool NormalizeSheet(const std::uint8_t* src_rgba, int src_w, int src_h,
                    std::uint8_t* dst_rgba);

// Variant used by the YOLO-guided experiment. The hint is a coarse marker/layout
// ROI in source-image pixel coordinates. Its four rectangle corners are used only
// as local search priors for the real black registration markers; the hint
// corners are never used directly as homography points.
bool NormalizeSheetWithHint(const std::uint8_t* src_rgba, int src_w, int src_h,
                            int hint_x1, int hint_y1, int hint_x2, int hint_y2,
                            std::uint8_t* dst_rgba);

// Variant driven by a YOLO corner-keypoint detector. `pts` holds the four
// corner-marker centres in SOURCE-image pixel coordinates, in any order
// (x0,y0,x1,y1,x2,y2,x3,y3). They are sorted into TL/TR/BR/BL geometrically,
// validated for a plausible quad, and used directly as the homography source
// points (no blob detection). Returns false if the points are degenerate, so
// the caller can fall back to NormalizeSheet.
bool NormalizeSheetWithCorners(const std::uint8_t* src_rgba, int src_w, int src_h,
                               const double pts[8], std::uint8_t* dst_rgba);

// Simple nearest-neighbour resize into a pre-allocated dst (dw*dh*4 bytes).
void ResizeRgba(const std::uint8_t* src, int sw, int sh,
                std::uint8_t* dst, int dw, int dh);

}  // namespace omr
