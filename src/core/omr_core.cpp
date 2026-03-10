#include "core/omr_core.h"
#include "core/omr_warp.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <vector>

namespace omr {
namespace {

constexpr int kBaseWidth  = 1700;
constexpr int kBaseHeight = 2400;
constexpr std::uint8_t kFrameThreshold = 120;  // for live viewfinder only
constexpr double kFillThreshold    = 15.0;
constexpr double kUncertainFillMax = 20.0;

struct Region {
  int x1;
  int y1;
  int x2;
  int y2;
  int rows;
  int cols;
};

struct Block {
  int question_start;
  Region region;
};

constexpr Region kMssvRegion{78, 843, 456, 1515, 10, 6};
constexpr Region kKeyRegion{519, 846, 711, 1515, 10, 3};
constexpr std::array<Block, 6> kQuestionBlocks{{
    {1, {880, 860, 1183, 1530, 10, 5}},
    {11, {1249, 856, 1563, 1527, 10, 5}},
    {21, {140, 1604, 447, 2311, 10, 5}},
    {31, {515, 1597, 819, 2303, 10, 5}},
    {41, {882, 1593, 1194, 2311, 10, 5}},
    {51, {1251, 1585, 1568, 2292, 10, 5}},
}};

int ClampInt(int v, int min_v, int max_v) {
  return std::max(min_v, std::min(max_v, v));
}

struct ScaledRect {
  int x;
  int y;
  int w;
  int h;
};

ScaledRect ScaleCell(const Region& region, int row, int col, int width, int height) {
  const double sx = static_cast<double>(width)  / static_cast<double>(kBaseWidth);
  const double sy = static_cast<double>(height) / static_cast<double>(kBaseHeight);

  // Scale the block boundaries to the working image size
  const int bx1 = static_cast<int>(std::round(region.x1 * sx));
  const int by1 = static_cast<int>(std::round(region.y1 * sy));
  const int bx2 = static_cast<int>(std::round(region.x2 * sx));
  const int by2 = static_cast<int>(std::round(region.y2 * sy));

  // Integer-division cell size (matches Android exactly):
  //   cellW = blockW / cols  →  all cells same width, possible unused px at end
  const int block_w = bx2 - bx1;
  const int block_h = by2 - by1;
  const int cell_w  = block_w / region.cols;
  const int cell_h  = block_h / region.rows;

  const int x = bx1 + col * cell_w;
  const int y = by1 + row * cell_h;

  const int safe_x  = ClampInt(x,          0, width  - 1);
  const int safe_y  = ClampInt(y,          0, height - 1);
  const int safe_x2 = ClampInt(x + cell_w, safe_x + 1, width);
  const int safe_y2 = ClampInt(y + cell_h, safe_y + 1, height);

  return ScaledRect{safe_x, safe_y, safe_x2 - safe_x, safe_y2 - safe_y};
}

// ── Grayscale helper ──────────────────────────────────────────────────────────
inline std::uint8_t Rgb2Gray(std::uint8_t r, std::uint8_t g, std::uint8_t b) {
  return static_cast<std::uint8_t>((299 * r + 587 * g + 114 * b) / 1000);
}

// ── Gaussian blur 5×5 (separable) on a grayscale plane ───────────────────────
void GaussianBlur5Gray(const std::uint8_t* src, std::uint8_t* dst,
                       int w, int h, std::vector<std::uint8_t>& tmp) {
  static constexpr int kW[5] = {1, 4, 6, 4, 1};
  tmp.assign(static_cast<std::size_t>(w * h), 0);
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x) {
      int acc = 0;
      for (int k = -2; k <= 2; ++k)
        acc += src[y * w + std::max(0, std::min(x + k, w - 1))] * kW[k + 2];
      tmp[static_cast<std::size_t>(y * w + x)] = static_cast<std::uint8_t>(acc / 16);
    }
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x) {
      int acc = 0;
      for (int k = -2; k <= 2; ++k)
        acc += tmp[static_cast<std::size_t>(std::max(0, std::min(y + k, h - 1)) * w + x)] * kW[k + 2];
      dst[y * w + x] = static_cast<std::uint8_t>(acc / 16);
    }
}

// ── Otsu threshold on grayscale ───────────────────────────────────────────────
std::uint8_t OtsuThreshold(const std::uint8_t* gray, int n) {
  long long hist[256] = {};
  for (int i = 0; i < n; ++i) ++hist[gray[i]];
  const double total = static_cast<double>(n);
  double sumAll = 0.0;
  for (int i = 0; i < 256; ++i) sumAll += i * static_cast<double>(hist[i]);
  double sumB = 0.0, wB = 0.0, maxVar = 0.0;
  std::uint8_t best = 128;
  for (int t = 0; t < 256; ++t) {
    wB += static_cast<double>(hist[t]);
    if (wB == 0.0) continue;
    const double wF = total - wB;
    if (wF == 0.0) break;
    sumB += t * static_cast<double>(hist[t]);
    const double mB = sumB / wB;
    const double mF = (sumAll - sumB) / wF;
    const double var = wB * wF * (mB - mF) * (mB - mF);
    if (var > maxVar) { maxVar = var; best = static_cast<std::uint8_t>(t); }
  }
  return best;
}

// ── Apply binary-INV to RGBA using pre-blurred grayscale ─────────────────────
void ApplyBinaryInvRgba(std::uint8_t* rgba, const std::uint8_t* gray_blurred,
                        int n, std::uint8_t thresh) {
  for (int i = 0; i < n; ++i) {
    const std::uint8_t v = (gray_blurred[i] < thresh) ? 255 : 0;
    rgba[i * 4 + 0] = v; rgba[i * 4 + 1] = v;
    rgba[i * 4 + 2] = v; rgba[i * 4 + 3] = 255;
  }
}

// ── Fixed-threshold binary for frame mode (speed > accuracy) ─────────────────
void ThresholdFrameFixed(std::uint8_t* rgba, int width, int height,
                         std::uint8_t threshold) {
  const int pixels = width * height;
  for (int i = 0; i < pixels; ++i) {
    const int idx        = i * 4;
    const std::uint8_t g = Rgb2Gray(rgba[idx], rgba[idx+1], rgba[idx+2]);
    const std::uint8_t v = (g < threshold) ? 0 : 255;
    rgba[idx+0] = v; rgba[idx+1] = v; rgba[idx+2] = v; rgba[idx+3] = 255;
  }
}

double WhiteRatioBinaryInv(const std::uint8_t* rgba, int width, const ScaledRect& rect) {
  const int total = rect.w * rect.h;
  if (total <= 0) {
    return 0.0;
  }

  int white = 0;
  for (int y = rect.y; y < rect.y + rect.h; ++y) {
    const int row = y * width;
    for (int x = rect.x; x < rect.x + rect.w; ++x) {
      const int idx = (row + x) * 4;
      if (rgba[idx] > 0) {
        ++white;
      }
    }
  }

  return (static_cast<double>(white) / static_cast<double>(total)) * 100.0;
}

int DetectNumericColumn(const std::uint8_t* rgba, int width, int height, const Region& region, int col) {
  int picked = -1;
  for (int row = 0; row < region.rows; ++row) {
    const ScaledRect cell = ScaleCell(region, row, col, width, height);
    const double ratio = WhiteRatioBinaryInv(rgba, width, cell);
    if (ratio >= kFillThreshold) {
      if (picked != -1) {
        return -1;
      }
      picked = row;
    }
  }
  return picked;
}

}  // namespace

// Stores the most recent 1700×2400 RGBA binary image for retrieval via CopyLastPreview.
static std::vector<std::uint8_t> g_last_preview;
// Stores the most recent 1700×2400 COLOR RGBA warped image (before binary) for preview overlay.
static std::vector<std::uint8_t> g_last_warped;

Roi OmrCore::ClampRoi(int width, int height, const Roi& roi) {
  if (width <= 0 || height <= 0) {
    return Roi{};
  }

  const int clamped_x = std::clamp(roi.x, 0, width - 1);
  const int clamped_y = std::clamp(roi.y, 0, height - 1);
  const int clamped_w = std::clamp(roi.w, 1, width - clamped_x);
  const int clamped_h = std::clamp(roi.h, 1, height - clamped_y);

  return Roi{clamped_x, clamped_y, clamped_w, clamped_h};
}

FrameProcessResult OmrCore::ProcessRgbaFrame(std::uint8_t* rgba, int width, int height, const Roi& roi) const {
  FrameProcessResult result;

  if (!rgba || width <= 0 || height <= 0) {
    result.status = -1;
    return result;
  }

  const Roi safe_roi = ClampRoi(width, height, roi);
  const int roi_x2 = safe_roi.x + safe_roi.w;
  const int roi_y2 = safe_roi.y + safe_roi.h;

  int black_count = 0;

  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      const int idx = (y * width + x) * 4;
      const std::uint8_t r = rgba[idx + 0];
      const std::uint8_t g = rgba[idx + 1];
      const std::uint8_t b = rgba[idx + 2];

      const std::uint8_t gray = static_cast<std::uint8_t>((299 * r + 587 * g + 114 * b) / 1000);
      const std::uint8_t binary = gray < kFrameThreshold ? 0 : 255;

      rgba[idx + 0] = binary;
      rgba[idx + 1] = binary;
      rgba[idx + 2] = binary;
      rgba[idx + 3] = 255;

      if (x >= safe_roi.x && x < roi_x2 && y >= safe_roi.y && y < roi_y2 && binary == 0) {
        ++black_count;
      }
    }
  }

  result.status = 0;
  result.black_count = black_count;
  result.width = width;
  result.height = height;
  result.roi = safe_roi;
  return result;
}

SheetProcessResult OmrCore::ProcessSheetRgba(std::uint8_t* rgba, int width, int height) const {
  SheetProcessResult out;
  if (!rgba || width <= 0 || height <= 0) { out.status = -1; return out; }

  const int base_bytes = kBaseWidth * kBaseHeight * 4;
  const int base_n     = kBaseWidth * kBaseHeight;

  // ── 1. Perspective normalization ───────────────────────────────────────────
  // Detect 4 corner markers and warp to canonical 1700×2400 layout.
  // Falls back to nearest-neighbour resize if detection fails.
  static thread_local std::vector<std::uint8_t> s_normbuf;
  s_normbuf.resize(static_cast<std::size_t>(base_bytes));

  const bool normalized = NormalizeSheet(rgba, width, height, s_normbuf.data());
  if (!normalized) {
    if (width == kBaseWidth && height == kBaseHeight) {
      std::memcpy(s_normbuf.data(), rgba,
                  static_cast<std::size_t>(base_bytes));
    } else {
      ResizeRgba(rgba, width, height,
                 s_normbuf.data(), kBaseWidth, kBaseHeight);
    }
  }

  // ── 2. Gaussian blur + Otsu threshold (adaptive, BINARY_INV) ──────────────
  static thread_local std::vector<std::uint8_t> s_gray, s_blurred, s_blur_tmp;
  s_gray.resize(static_cast<std::size_t>(base_n));
  s_blurred.resize(static_cast<std::size_t>(base_n));

  for (int i = 0; i < base_n; ++i)
    s_gray[static_cast<std::size_t>(i)] =
        Rgb2Gray(s_normbuf[i*4], s_normbuf[i*4+1], s_normbuf[i*4+2]);

  GaussianBlur5Gray(s_gray.data(), s_blurred.data(),
                    kBaseWidth, kBaseHeight, s_blur_tmp);

  const std::uint8_t thresh = OtsuThreshold(s_blurred.data(), base_n);

  // Save color warped BEFORE binarization (for color overlay preview)
  g_last_warped.assign(s_normbuf.begin(),
                       s_normbuf.begin() + static_cast<std::ptrdiff_t>(base_bytes));

  ApplyBinaryInvRgba(s_normbuf.data(), s_blurred.data(), base_n, thresh);

  // Write binary preview back into caller's buffer for JS preview
  if (width == kBaseWidth && height == kBaseHeight)
    std::memcpy(rgba, s_normbuf.data(), static_cast<std::size_t>(base_bytes));

  // Always store in global so CopyLastPreview can return it regardless of
  // whether input dimensions matched the base size.
  g_last_preview.assign(s_normbuf.begin(),
                        s_normbuf.begin() + static_cast<std::ptrdiff_t>(base_bytes));

  // ── 3. Grid analysis on the normalised + binarised image ──────────────────
  const std::uint8_t* work = s_normbuf.data();

  out.mssv_valid = 1;
  for (int col = 0; col < kMssvRegion.cols; ++col) {
    const int digit = DetectNumericColumn(work, kBaseWidth, kBaseHeight,
                                          kMssvRegion, col);
    out.mssv_digits[col] = digit;
    if (digit < 0) out.mssv_valid = 0;
  }

  out.key_valid = 1;
  for (int col = 0; col < kKeyRegion.cols; ++col) {
    const int digit = DetectNumericColumn(work, kBaseWidth, kBaseHeight,
                                          kKeyRegion, col);
    out.key_digits[col] = digit;
    if (digit < 0) out.key_valid = 0;
  }

  out.answer_masks.fill(0);
  out.suspicious.fill(0);

  for (const Block& block : kQuestionBlocks) {
    for (int row = 0; row < block.region.rows; ++row) {
      const int q_idx = (block.question_start - 1) + row;
      if (q_idx < 0 || q_idx >= static_cast<int>(out.answer_masks.size()))
        continue;

      int    mask         = 0;
      int    filled_count = 0;
      double single_ratio = 0.0;

      for (int col = 0; col < block.region.cols; ++col) {
        const ScaledRect cell = ScaleCell(block.region, row, col,
                                          kBaseWidth, kBaseHeight);
        const double ratio = WhiteRatioBinaryInv(work, kBaseWidth, cell);
        if (ratio >= kFillThreshold) {
          mask |= (1 << col);
          ++filled_count;
          if (filled_count == 1) single_ratio = ratio;
        }
      }

      out.answer_masks[q_idx] = mask;
      if (filled_count > 1)
        out.suspicious[q_idx] = 1;
      else if (filled_count == 1 && single_ratio <= kUncertainFillMax)
        out.suspicious[q_idx] = 1;
    }
  }

  out.status = 0;
  return out;
}

int OmrCore::CopyLastPreview(std::uint8_t* dst, int dst_len) const {
  constexpr int kPreviewBytes = kBaseWidth * kBaseHeight * 4;
  if (!dst || dst_len < kPreviewBytes) return -1;
  if (static_cast<int>(g_last_preview.size()) < kPreviewBytes) return -1;
  std::memcpy(dst, g_last_preview.data(), static_cast<std::size_t>(kPreviewBytes));
  return kPreviewBytes;
}

int OmrCore::CopyLastWarped(std::uint8_t* dst, int dst_len) const {
  constexpr int kPreviewBytes = kBaseWidth * kBaseHeight * 4;
  if (!dst || dst_len < kPreviewBytes) return -1;
  if (static_cast<int>(g_last_warped.size()) < kPreviewBytes) return -1;
  std::memcpy(dst, g_last_warped.data(), static_cast<std::size_t>(kPreviewBytes));
  return kPreviewBytes;
}

}  // namespace omr

