#include "core/omr_warp.h"

#include <algorithm>
#include <cassert>
#include <climits>
#include <cmath>
#include <cstring>
#include <functional>
#include <vector>

namespace omr {

namespace {

constexpr int kTargetW = 1700;
constexpr int kTargetH = 2400;

// ─── Nearest-neighbour downscale (grayscale) ──────────────────────────────────
void DownscaleGray(const std::uint8_t* src, int sw, int sh,
                   std::uint8_t* dst, int dw, int dh) {
  for (int y = 0; y < dh; ++y) {
    const int sy = y * sh / dh;
    for (int x = 0; x < dw; ++x) {
      const int sx = x * sw / dw;
      dst[y * dw + x] = src[sy * sw + sx];
    }
  }
}

// ─── RGBA → grayscale (BT.601) ────────────────────────────────────────────────
void RgbaToGray(const std::uint8_t* rgba, std::uint8_t* gray, int w, int h) {
  const int n = w * h;
  for (int i = 0; i < n; ++i) {
    const int r = rgba[i * 4 + 0];
    const int g = rgba[i * 4 + 1];
    const int b = rgba[i * 4 + 2];
    gray[i] = static_cast<std::uint8_t>((299 * r + 587 * g + 114 * b) / 1000);
  }
}

// ─── Separable Gaussian blur 5×5 (kernel [1,4,6,4,1]/16) ─────────────────────
void GaussianBlur5(const std::uint8_t* src, std::uint8_t* dst, int w, int h) {
  static constexpr int kW[5] = {1, 4, 6, 4, 1};
  std::vector<std::uint8_t> tmp(w * h);

  // Horizontal pass
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      int acc = 0;
      for (int k = -2; k <= 2; ++k) {
        const int xx = std::max(0, std::min(x + k, w - 1));
        acc += src[y * w + xx] * kW[k + 2];
      }
      tmp[y * w + x] = static_cast<std::uint8_t>(acc / 16);
    }
  }

  // Vertical pass
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      int acc = 0;
      for (int k = -2; k <= 2; ++k) {
        const int yy = std::max(0, std::min(y + k, h - 1));
        acc += tmp[yy * w + x] * kW[k + 2];
      }
      dst[y * w + x] = static_cast<std::uint8_t>(acc / 16);
    }
  }
}

// ─── Histogram Equalization (for adaptive threshold) ──────────────────────────
void HistogramEqualize(const std::uint8_t* src, std::uint8_t* dst, int n) {
  long long hist[256] = {};
  for (int i = 0; i < n; ++i) ++hist[src[i]];

  // Compute CDF
  std::uint8_t lut[256];
  long long cdf = 0;
  for (int i = 0; i < 256; ++i) {
    cdf += hist[i];
    lut[i] = static_cast<std::uint8_t>(cdf * 255 / n);
  }

  // Apply LUT
  for (int i = 0; i < n; ++i) dst[i] = lut[src[i]];
}

// ─── Otsu threshold ───────────────────────────────────────────────────────────
std::uint8_t OtsuThreshold(const std::uint8_t* gray, int n) {
  long long hist[256] = {};
  for (int i = 0; i < n; ++i) ++hist[gray[i]];

  double total = static_cast<double>(n);
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
    if (var > maxVar) {
      maxVar = var;
      best = static_cast<std::uint8_t>(t);
    }
  }
  return best;
}

// ─── Adaptive Otsu: handles both bright (student) and dark (answer key) images ──
std::uint8_t AdaptiveOtsuThreshold(const std::uint8_t* gray, int n) {
  std::uint8_t thresh = OtsuThreshold(gray, n);

  // If Otsu is too low (dark image like answer key), equalize histogram and retry
  if (thresh < 130) {
    std::vector<std::uint8_t> equalized(n);
    HistogramEqualize(gray, equalized.data(), n);
    thresh = OtsuThreshold(equalized.data(), n);
    // Scale back to original histogram range (roughly)
    thresh = static_cast<std::uint8_t>(thresh * 0.6);
  }

  return thresh;
}

// ─── Binary INV: dark pixels (< thresh) → 255, light → 0 ────────────────────
void BinaryInv(const std::uint8_t* gray, std::uint8_t* bin, int n,
               std::uint8_t thresh) {
  for (int i = 0; i < n; ++i) {
    bin[i] = (gray[i] < thresh) ? 255 : 0;
  }
}

// ─── Morphological erosion 3×3 (binary 0/255) ────────────────────────────────
void Erode3(const std::uint8_t* src, std::uint8_t* dst, int w, int h) {
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      bool all = true;
      for (int dy = -1; dy <= 1 && all; ++dy) {
        for (int dx = -1; dx <= 1 && all; ++dx) {
          const int yy = std::max(0, std::min(y + dy, h - 1));
          const int xx = std::max(0, std::min(x + dx, w - 1));
          if (src[yy * w + xx] == 0) all = false;
        }
      }
      dst[y * w + x] = all ? 255 : 0;
    }
  }
}

// ─── Morphological dilation 3×3 (binary 0/255) ───────────────────────────────
void Dilate3(const std::uint8_t* src, std::uint8_t* dst, int w, int h) {
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      bool any = false;
      for (int dy = -1; dy <= 1 && !any; ++dy) {
        for (int dx = -1; dx <= 1 && !any; ++dx) {
          const int yy = std::max(0, std::min(y + dy, h - 1));
          const int xx = std::max(0, std::min(x + dx, w - 1));
          if (src[yy * w + xx] == 255) any = true;
        }
      }
      dst[y * w + x] = any ? 255 : 0;
    }
  }
}

// ─── OPEN = erode then dilate (removes small noise, keeps large objects) ──────
void MorphOpen(std::uint8_t* buf, std::uint8_t* tmp, int w, int h) {
  Erode3(buf, tmp, w, h);
  Dilate3(tmp, buf, w, h);
}

// ─── CLOSE = dilate then erode (fills small holes in contours) ───────────────
void MorphClose(std::uint8_t* buf, std::uint8_t* tmp, int w, int h) {
  Dilate3(buf, tmp, w, h);
  Erode3(tmp, buf, w, h);
}

// ─── Connected components (2-pass + union-find) ───────────────────────────────
struct BlobInfo {
  double cx, cy;
  int x1, y1, x2, y2;
  int pixel_count;
  double mean_gray;  // mean of original gray within bounding box
};

struct BlobStats {
  long long sx = 0, sy = 0;
  int x1 = INT_MAX, y1 = INT_MAX, x2 = -1, y2 = -1;
  int count = 0;
};

// FindBlobs: gray_src may be nullptr (mean_gray will be 0 then)
std::vector<BlobInfo> FindBlobs(const std::uint8_t* bin,
                                const std::uint8_t* gray_src,
                                int w, int h,
                                int min_area, int max_area) {
  std::vector<int> label(w * h, 0);
  std::vector<int> parent;
  parent.reserve(4096);
  parent.push_back(0);
  int next_label = 1;

  std::function<int(int)> Find = [&](int x) -> int {
    while (parent[x] != x) {
      parent[x] = parent[parent[x]];
      x = parent[x];
    }
    return x;
  };
  auto Union = [&](int a, int b) {
    a = Find(a); b = Find(b);
    if (a != b) parent[b] = a;
  };

  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      if (bin[y * w + x] == 0) continue;
      const int up = (y > 0) ? label[(y - 1) * w + x] : 0;
      const int lf = (x > 0) ? label[y * w + x - 1] : 0;
      if (up == 0 && lf == 0) {
        label[y * w + x] = next_label;
        parent.push_back(next_label);
        ++next_label;
      } else if (up != 0 && lf == 0) { label[y * w + x] = up; }
      else if (up == 0 && lf != 0) { label[y * w + x] = lf; }
      else { label[y * w + x] = up; Union(up, lf); }
    }
  }

  std::vector<BlobStats> vstats(static_cast<std::size_t>(next_label));
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x) {
      int l = label[y * w + x];
      if (l == 0) continue;
      l = Find(l);
      auto& s = vstats[static_cast<std::size_t>(l)];
      s.sx += x; s.sy += y;
      s.x1 = std::min(s.x1, x); s.y1 = std::min(s.y1, y);
      s.x2 = std::max(s.x2, x); s.y2 = std::max(s.y2, y);
      ++s.count;
    }

  std::vector<BlobInfo> blobs;
  for (int l = 1; l < next_label; ++l) {
    const auto& s = vstats[static_cast<std::size_t>(l)];
    if (s.count == 0 || Find(l) != l) continue;
    if (s.count < min_area || s.count > max_area) continue;
    const int bw = s.x2 - s.x1 + 1;
    const int bh = s.y2 - s.y1 + 1;
    if (bw <= 0 || bh <= 0) continue;

    // Compute mean gray within bounding box (if gray provided)
    double mean_g = 0.0;
    if (gray_src) {
      long long gsum = 0;
      int gcnt = 0;
      for (int yy = s.y1; yy <= s.y2; ++yy)
        for (int xx = s.x1; xx <= s.x2; ++xx) {
          gsum += gray_src[yy * w + xx];
          ++gcnt;
        }
      mean_g = gcnt > 0 ? static_cast<double>(gsum) / gcnt : 255.0;
    }

    BlobInfo b;
    b.cx = static_cast<double>(s.sx) / s.count;
    b.cy = static_cast<double>(s.sy) / s.count;
    b.x1 = s.x1; b.y1 = s.y1; b.x2 = s.x2; b.y2 = s.y2;
    b.pixel_count = s.count;
    b.mean_gray = mean_g;
    blobs.push_back(b);
  }
  return blobs;
}

// ─── Order corner points as [TL, TR, BR, BL] ─────────────────────────────────
struct Point2d {
  double x, y;
};

// ─── Solve 8×8 linear system via Gaussian elimination (in-place) ─────────────
// A is [8][9] (last col = RHS). Writes solution into x[8].
bool SolveLinear8(double A[8][9], double x[8]) {
  for (int col = 0; col < 8; ++col) {
    // Partial pivot
    int pivot = -1;
    double bestAbs = 0.0;
    for (int row = col; row < 8; ++row) {
      const double ab = std::fabs(A[row][col]);
      if (ab > bestAbs) {
        bestAbs = ab;
        pivot = row;
      }
    }
    if (pivot < 0 || bestAbs < 1e-10) return false;

    if (pivot != col) {
      for (int j = 0; j <= 8; ++j) std::swap(A[col][j], A[pivot][j]);
    }

    const double s = A[col][col];
    for (int j = col; j <= 8; ++j) A[col][j] /= s;

    for (int row = 0; row < 8; ++row) {
      if (row == col) continue;
      const double f = A[row][col];
      for (int j = col; j <= 8; ++j) A[row][j] -= f * A[col][j];
    }
  }
  for (int i = 0; i < 8; ++i) x[i] = A[i][8];
  return true;
}

// ─── Compute forward homography H (src → canonical dst = [0,0,W,H]) ──────────
// H*[x,y,1]' = λ*[u,v,1]'  where H[8]=1
bool ComputeHomography(const Point2d src[4], double W, double H, double fwd[9]) {
  const double dstX[4] = {0.0, W, W, 0.0};
  const double dstY[4] = {0.0, 0.0, H, H};

  double A[8][9] = {};
  for (int i = 0; i < 4; ++i) {
    const double xi = src[i].x, yi = src[i].y;
    const double ui = dstX[i], vi = dstY[i];
    // Row 2i:   [xi, yi, 1, 0, 0, 0, -xi*ui, -yi*ui | ui]
    A[i * 2][0] = xi;
    A[i * 2][1] = yi;
    A[i * 2][2] = 1.0;
    A[i * 2][6] = -xi * ui;
    A[i * 2][7] = -yi * ui;
    A[i * 2][8] = ui;
    // Row 2i+1: [0, 0, 0, xi, yi, 1, -xi*vi, -yi*vi | vi]
    A[i * 2 + 1][3] = xi;
    A[i * 2 + 1][4] = yi;
    A[i * 2 + 1][5] = 1.0;
    A[i * 2 + 1][6] = -xi * vi;
    A[i * 2 + 1][7] = -yi * vi;
    A[i * 2 + 1][8] = vi;
  }

  double h[8];
  if (!SolveLinear8(A, h)) return false;

  fwd[0] = h[0]; fwd[1] = h[1]; fwd[2] = h[2];
  fwd[3] = h[3]; fwd[4] = h[4]; fwd[5] = h[5];
  fwd[6] = h[6]; fwd[7] = h[7]; fwd[8] = 1.0;
  return true;
}

// ─── Invert 3×3 homography ────────────────────────────────────────────────────
bool InvertHomography(const double H[9], double Hinv[9]) {
  const double a = H[0], b = H[1], c = H[2];
  const double d = H[3], e = H[4], f = H[5];
  const double g = H[6], hh = H[7], k = H[8];

  const double det = a * (e * k - f * hh) - b * (d * k - f * g) + c * (d * hh - e * g);
  if (std::fabs(det) < 1e-12) return false;

  const double inv = 1.0 / det;
  Hinv[0] = (e * k - f * hh) * inv;
  Hinv[1] = (c * hh - b * k) * inv;
  Hinv[2] = (b * f - c * e) * inv;
  Hinv[3] = (f * g - d * k) * inv;
  Hinv[4] = (a * k - c * g) * inv;
  Hinv[5] = (c * d - a * f) * inv;
  Hinv[6] = (d * hh - e * g) * inv;
  Hinv[7] = (b * g - a * hh) * inv;
  Hinv[8] = (a * e - b * d) * inv;
  return true;
}

// ─── Bilinear perspective warp (inverse mapping) ──────────────────────────────
// For each dst pixel (u,v) we compute the source coordinate using Hinv
// (dst → src homography) and sample bilinearly.
void WarpRgba(const std::uint8_t* src, int sw, int sh,
              std::uint8_t* dst, int dw, int dh,
              const double Hinv[9]) {
  for (int v = 0; v < dh; ++v) {
    for (int u = 0; u < dw; ++u) {
      const double denom = Hinv[6] * u + Hinv[7] * v + Hinv[8];
      const double sx_f  = (Hinv[0] * u + Hinv[1] * v + Hinv[2]) / denom;
      const double sy_f  = (Hinv[3] * u + Hinv[4] * v + Hinv[5]) / denom;

      const int x0 = static_cast<int>(std::floor(sx_f));
      const int y0 = static_cast<int>(std::floor(sy_f));
      const double fx = sx_f - x0;
      const double fy = sy_f - y0;

      const int xa = std::max(0, std::min(x0,     sw - 1));
      const int xb = std::max(0, std::min(x0 + 1, sw - 1));
      const int ya = std::max(0, std::min(y0,     sh - 1));
      const int yb = std::max(0, std::min(y0 + 1, sh - 1));

      std::uint8_t* out = dst + (v * dw + u) * 4;
      const std::uint8_t* p00 = src + (ya * sw + xa) * 4;
      const std::uint8_t* p10 = src + (ya * sw + xb) * 4;
      const std::uint8_t* p01 = src + (yb * sw + xa) * 4;
      const std::uint8_t* p11 = src + (yb * sw + xb) * 4;

      for (int c = 0; c < 4; ++c) {
        const double val = p00[c] * (1.0 - fx) * (1.0 - fy) +
                           p10[c] * fx           * (1.0 - fy) +
                           p01[c] * (1.0 - fx)   * fy         +
                           p11[c] * fx            * fy;
        out[c] = static_cast<std::uint8_t>(
            std::max(0, std::min(255, static_cast<int>(val + 0.5))));
      }
    }
  }
}

// ─── Sanity check: are the 4 corners arranged reasonably? ────────────────────
bool CornersLookValid(const Point2d corners[4], int src_w, int src_h) {
  const double min_span_x = src_w * 0.15;
  const double min_span_y = src_h * 0.15;
  if (corners[1].x - corners[0].x < min_span_x) return false;
  if (corners[2].x - corners[3].x < min_span_x) return false;
  if (corners[2].y - corners[1].y < min_span_y) return false;
  if (corners[3].y - corners[0].y < min_span_y) return false;
  return true;
}

// ─── LAYER 1: Find 4 black marker corners (port of Android findMarkerCorners) ─
// Uses BINARY_INV + Otsu → MORPH_OPEN×2 → blob filter (aspect 0.6-1.4,
// fill≥0.4, mean_gray<160) → top-20 → quadrant assignment → farthest.
bool FindMarkerCorners(const std::uint8_t* gray, int w, int h,
                       Point2d out[4]) {
  const int n = w * h;
  std::vector<std::uint8_t> blurred(n), bin(n), tmp(n);

  GaussianBlur5(gray, blurred.data(), w, h);

  const std::uint8_t thresh = OtsuThreshold(blurred.data(), n);
  BinaryInv(blurred.data(), bin.data(), n, thresh);  // dark marker → 255

  // MORPH_OPEN ×2 (removes thin lines, keeps solid dark squares)
  MorphOpen(bin.data(), tmp.data(), w, h);
  MorphOpen(bin.data(), tmp.data(), w, h);

  // Reasonable marker size range: 50px – 1/4 of image
  const auto blobs = FindBlobs(bin.data(), gray, w, h, 50, n / 4);

  // Filter: aspect 0.6-1.4, fill≥0.4, mean_gray<160 (must be dark)
  std::vector<BlobInfo> candidates;
  candidates.reserve(blobs.size());
  for (const auto& b : blobs) {
    const int bw = b.x2 - b.x1 + 1;
    const int bh = b.y2 - b.y1 + 1;
    const double ar   = static_cast<double>(bw) / bh;
    const double fill = static_cast<double>(b.pixel_count) / (bw * bh);
    if (ar < 0.6 || ar > 1.4) continue;
    if (fill < 0.4) continue;
    if (b.mean_gray > 160.0) continue;
    candidates.push_back(b);
  }

  if (static_cast<int>(candidates.size()) < 4) return false;

  // Sort by area descending, take top 20
  std::sort(candidates.begin(), candidates.end(),
            [](const BlobInfo& a, const BlobInfo& b) {
              return a.pixel_count > b.pixel_count;
            });
  if (candidates.size() > 20) candidates.resize(20);

  // Centroid of candidates
  double cx = 0.0, cy = 0.0;
  for (const auto& b : candidates) { cx += b.cx; cy += b.cy; }
  cx /= candidates.size();
  cy /= candidates.size();

  // Assign to quadrants; pick farthest from centroid in each quadrant
  BlobInfo const* quad[4] = {nullptr, nullptr, nullptr, nullptr};
  double quadD[4] = {-1.0, -1.0, -1.0, -1.0};  // TL, TR, BR, BL

  for (const auto& b : candidates) {
    const int q = (b.cx > cx ? 1 : 0) + (b.cy > cy ? 2 : 0);  // 0=TL,1=TR,3=BR,2=BL
    // map: 0→TL, 1→TR, 3→BR, 2→BL  (reorder to TL=0,TR=1,BR=2,BL=3)
    const int slot = (q == 3) ? 2 : (q == 2) ? 3 : q;
    const double dx = b.cx - cx, dy = b.cy - cy;
    const double d  = dx * dx + dy * dy;
    if (d > quadD[slot]) { quadD[slot] = d; quad[slot] = &b; }
  }

  if (!quad[0] || !quad[1] || !quad[2] || !quad[3]) return false;

  out[0] = {quad[0]->cx, quad[0]->cy};  // TL
  out[1] = {quad[1]->cx, quad[1]->cy};  // TR
  out[2] = {quad[2]->cx, quad[2]->cy};  // BR
  out[3] = {quad[3]->cx, quad[3]->cy};  // BL
  return true;
}

// ─── LAYER 2: Find paper corners as fallback (port of Android findPaperCorners)
// BINARY + Otsu → MORPH_CLOSE×4 → largest white blob → diagonal extremes.
bool FindPaperCorners(const std::uint8_t* gray, int w, int h,
                      Point2d out[4]) {
  const int n = w * h;
  std::vector<std::uint8_t> blurred(n), bin(n), tmp(n);

  // Two passes of Gaussian to approximate Android's 7×7 blur
  GaussianBlur5(gray, blurred.data(), w, h);
  GaussianBlur5(blurred.data(), tmp.data(), w, h);

  // Adaptive Otsu: if image is dark (answer key), equalize histogram first
  // This ensures consistent corner detection across both student sheets and answer keys
  const std::uint8_t thresh = AdaptiveOtsuThreshold(tmp.data(), n);
  for (int i = 0; i < n; ++i) bin[i] = (tmp[i] >= thresh) ? 255 : 0;  // paper=white

  // MORPH_CLOSE ×4 (approximates Android's 9×9 kernel MorphClose)
  MorphClose(bin.data(), tmp.data(), w, h);
  MorphClose(bin.data(), tmp.data(), w, h);
  MorphClose(bin.data(), tmp.data(), w, h);
  MorphClose(bin.data(), tmp.data(), w, h);

  // Find largest blob (= the paper)
  const auto blobs = FindBlobs(bin.data(), nullptr, w, h, n / 20, n);
  if (blobs.empty()) return false;

  const BlobInfo& largest = *std::max_element(
      blobs.begin(), blobs.end(),
      [](const BlobInfo& a, const BlobInfo& b) {
        return a.pixel_count < b.pixel_count;
      });

  // Diagonal extremes ONLY within the largest blob's bounding box pixels.
  // Re-scan bin in that bbox to find TL/TR/BR/BL corners.
  int sum_tl = INT_MAX, sum_br = INT_MIN;
  int diff_tr = INT_MIN, diff_bl = INT_MAX;
  Point2d tl{}, tr{}, br{}, bl{};

  for (int y = largest.y1; y <= largest.y2; ++y)
    for (int x = largest.x1; x <= largest.x2; ++x) {
      if (bin[y * w + x] == 0) continue;
      const int s = x + y, d = x - y;
      if (s < sum_tl)  { sum_tl  = s; tl = {static_cast<double>(x), static_cast<double>(y)}; }
      if (s > sum_br)  { sum_br  = s; br = {static_cast<double>(x), static_cast<double>(y)}; }
      if (d > diff_tr) { diff_tr = d; tr = {static_cast<double>(x), static_cast<double>(y)}; }
      if (d < diff_bl) { diff_bl = d; bl = {static_cast<double>(x), static_cast<double>(y)}; }
    }

  out[0] = tl; out[1] = tr; out[2] = br; out[3] = bl;
  return true;
}

}  // namespace

void ResizeRgba(const std::uint8_t* src, int sw, int sh,
                std::uint8_t* dst, int dw, int dh) {
  for (int y = 0; y < dh; ++y) {
    const int sy = y * sh / dh;
    for (int x = 0; x < dw; ++x) {
      const int sx = x * sw / dw;
      const std::uint8_t* ps = src + (sy * sw + sx) * 4;
      std::uint8_t* pd = dst + (y * dw + x) * 4;
      pd[0] = ps[0]; pd[1] = ps[1]; pd[2] = ps[2]; pd[3] = ps[3];
    }
  }
}

// ─── NormalizeSheet ────────────────────────────────────────────────────────────
//
// Uses YOLO-cropped output (which preserves aspect ratio and includes paper)
// to accurately find paper corners via Computer Vision, then applies precise
// perspective homography to map back to 1700x2400 cleanly.
bool NormalizeSheet(const std::uint8_t* src_rgba, int src_w, int src_h,
                    std::uint8_t* dst_rgba) {
  if (!src_rgba || src_w <= 0 || src_h <= 0 || !dst_rgba) return false;

  // If already exactly TargetW x TargetH, copy directly
  if (src_w == kTargetW && src_h == kTargetH) {
    std::memcpy(dst_rgba, src_rgba, static_cast<std::size_t>(kTargetW * kTargetH * 4));
    return true;
  }

  // 1. Prepare small analysis image for speed
  constexpr int kAnalysisW = 800;
  const int kAnalysisH = src_h * kAnalysisW / src_w;
  const int n = kAnalysisW * kAnalysisH;
  std::vector<std::uint8_t> gray_small(n);
  std::vector<std::uint8_t> rgba_small(n * 4);

  ResizeRgba(src_rgba, src_w, src_h, rgba_small.data(), kAnalysisW, kAnalysisH);
  RgbaToGray(rgba_small.data(), gray_small.data(), kAnalysisW, kAnalysisH);

  // 2. Find paper corners
  Point2d corners_small[4];
  if (FindPaperCorners(gray_small.data(), kAnalysisW, kAnalysisH, corners_small)) {
    if (CornersLookValid(corners_small, kAnalysisW, kAnalysisH)) {
      // 3. Map corners back to full original image scale
      Point2d corners_full[4];
      for (int i = 0; i < 4; ++i) {
        corners_full[i].x = corners_small[i].x * src_w / kAnalysisW;
        corners_full[i].y = corners_small[i].y * src_h / kAnalysisH;
      }

      // 4. Compute Homography and Warp
      double H[9], Hinv[9];
      if (ComputeHomography(corners_full, kTargetW, kTargetH, H) && InvertHomography(H, Hinv)) {
        WarpRgba(src_rgba, src_w, src_h, dst_rgba, kTargetW, kTargetH, Hinv);
        return true;
      }
    }
  }

  // Fallback: if corner detection or homography fails, resize normally
  ResizeRgba(src_rgba, src_w, src_h, dst_rgba, kTargetW, kTargetH);
  return true;
}

}  // namespace omr
