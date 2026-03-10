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

// Simple nearest-neighbour resize into a pre-allocated dst (dw*dh*4 bytes).
void ResizeRgba(const std::uint8_t* src, int sw, int sh,
                std::uint8_t* dst, int dw, int dh);

}  // namespace omr
