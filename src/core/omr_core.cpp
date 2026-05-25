#include "core/omr_core.h"
#include "core/omr_warp.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <vector>
#include <string>

#include <opencv2/opencv.hpp>

namespace omr {

static std::vector<std::uint8_t> g_last_preview;
static std::vector<std::uint8_t> g_last_warped;

constexpr std::uint8_t kFrameThreshold = 120;  // for live viewfinder only

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

// Support structures for Stage 3 logic
struct Bubble {
    int cx;
    int cy;
    double density;
};

struct RegionData {
    std::string name;
    int q_offset;
    std::vector<int> expected_x;
    std::vector<int> expected_y;
    int R;
    std::vector<Bubble> bubbles;
};

SheetProcessResult OmrCore::ProcessSheetRgba(std::uint8_t* rgba, int width, int height) const {
  SheetProcessResult out;
  if (!rgba || width <= 0 || height <= 0) { out.status = -1; return out; }

  // 1. Perspective normalization
  constexpr int kBaseWidth = 1700;
  constexpr int kBaseHeight = 2400;
  const int base_bytes = kBaseWidth * kBaseHeight * 4;

  std::vector<std::uint8_t> s_normbuf(base_bytes);
  bool normalized = NormalizeSheet(rgba, width, height, s_normbuf.data());
  if (!normalized) {
      if (width == kBaseWidth && height == kBaseHeight) {
          std::memcpy(s_normbuf.data(), rgba, base_bytes);
      } else {
          ResizeRgba(rgba, width, height, s_normbuf.data(), kBaseWidth, kBaseHeight);
      }
  }

  // Save color warped image AFTER Stage 2 crop so preview matches Stage 3 input exactly.
  // (moved down — see after Stage 2 block)

  // 2. Stage 2: Tight crop by corner markers (notebook's crop_by_markers)
  //    The notebook maps the 4 corner registration squares → tight 1700x2400
  //    so that ax/ay anchor points are valid. We must do the same.
  {
    cv::Mat paper(kBaseHeight, kBaseWidth, CV_8UC4, (void*)s_normbuf.data());
    cv::Mat g2, th, cleaned;
    cv::cvtColor(paper, g2, cv::COLOR_RGBA2GRAY);
    cv::threshold(g2, th, 0, 255, cv::THRESH_BINARY_INV | cv::THRESH_OTSU);
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(5, 5));
    cv::morphologyEx(th, cleaned, cv::MORPH_OPEN, kernel);

    std::vector<std::vector<cv::Point>> ctrs;
    cv::findContours(cleaned, ctrs, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

    // Collect valid markers: area 150-15000 (edge-clipped) / 1500-15000 (interior),
    // aspect 0.7-1.4, fill > 0.55/0.7 — restricted to outer 20% corner zones.
    const int zone_w = kBaseWidth  / 5;  // 340px
    const int zone_h = kBaseHeight / 5;  // 480px
    std::vector<cv::Point2f> markers;
    for (const auto& c : ctrs) {
      cv::Rect bound = cv::boundingRect(c);
      double area = cv::contourArea(c);
      double ar = (double)bound.width / bound.height;
      // Edge-touching blobs (from Stage 1 mapping marker centers to canvas corners)
      // are partially clipped and have smaller area — accept them with a lower threshold.
      bool touches_edge = (bound.x <= 1 || bound.y <= 1 ||
                           bound.x + bound.width >= kBaseWidth - 1 ||
                           bound.y + bound.height >= kBaseHeight - 1);
      double min_area = touches_edge ? 150.0 : 1500.0;
      if (area < min_area || area > 15000) continue;
      if (ar < 0.7 || ar > 1.4) continue;
      double min_fill = touches_edge ? 0.55 : 0.7;
      if ((area / (double)(bound.width * bound.height)) < min_fill) continue;
      float cx = bound.x + bound.width / 2.0f;
      float cy = bound.y + bound.height / 2.0f;
      // Must be in one of the 4 corner zones
      bool in_left   = cx < zone_w;
      bool in_right  = cx > kBaseWidth  - zone_w;
      bool in_top    = cy < zone_h;
      bool in_bottom = cy > kBaseHeight - zone_h;
      if ((in_left || in_right) && (in_top || in_bottom)) {
        markers.push_back(cv::Point2f(cx, cy));
      }
    }

    auto localPercentile = [](const cv::Mat& roi, double pct) -> int {
      int hist[256] = {0};
      for (int y = 0; y < roi.rows; ++y) {
        const std::uint8_t* row = roi.ptr<std::uint8_t>(y);
        for (int x = 0; x < roi.cols; ++x) hist[row[x]]++;
      }
      const int target = std::max(1, static_cast<int>(roi.total() * pct));
      int acc = 0;
      for (int i = 0; i < 256; ++i) {
        acc += hist[i];
        if (acc >= target) return i;
      }
      return 255;
    };

    auto findLocalCornerMarker = [&](const cv::Rect& zone, const cv::Point2f& target, cv::Point2f& out) -> bool {
      const cv::Rect safe_zone = zone & cv::Rect(0, 0, kBaseWidth, kBaseHeight);
      if (safe_zone.width <= 0 || safe_zone.height <= 0) return false;

      const cv::Mat roi = g2(safe_zone);
      cv::Mat otsu_tmp;
      double otsu = cv::threshold(roi, otsu_tmp, 0, 255, cv::THRESH_BINARY_INV | cv::THRESH_OTSU);
      // Reduced from 6 → 3 adaptive thresholds. The hardcoded 160/180/200 levels
      // (originally a safety net) were exercised <5% of the time in practice and
      // each adds ~25 ms of `threshold + morph + findContours` work on a 520×560
      // ROI. Local Otsu plus the two percentiles cover both well-lit and dim
      // corner zones across the new and old datasets.
      const std::vector<int> thresholds = {
        static_cast<int>(otsu),
        localPercentile(roi, 0.10),
        localPercentile(roi, 0.20)
      };

      bool found = false;
      double best_score = 1e18;
      cv::Point2f best;
      const double zone_diag = std::sqrt(static_cast<double>(safe_zone.width * safe_zone.width +
                                                             safe_zone.height * safe_zone.height));

      // Early-termination optimisation: if a confident marker (score < 0.15) is
      // found at the first threshold, skip the remaining 5 — measured 631 ms
      // total across 4 corners before this change; the first Otsu attempt almost
      // always succeeds on faint-pencil sheets, so this typically cuts ~80% off
      // Stage 2's fallback cost. Fallback semantics on hard images are unchanged:
      // when threshold N finds nothing useful, we still proceed to threshold N+1.
      for (int threshold_value : thresholds) {
        cv::Mat local_bin, local_cleaned;
        cv::threshold(roi, local_bin, std::clamp(threshold_value, 0, 255), 255, cv::THRESH_BINARY_INV);
        cv::morphologyEx(local_bin, local_cleaned, cv::MORPH_OPEN, kernel);

        std::vector<std::vector<cv::Point>> local_ctrs;
        cv::findContours(local_cleaned, local_ctrs, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

        for (const auto& c : local_ctrs) {
          cv::Rect bound = cv::boundingRect(c);
          double area = cv::contourArea(c);
          if (area < 300.0 || area > 6000.0) continue;
          double ar = static_cast<double>(bound.width) / bound.height;
          if (ar < 0.65 || ar > 1.50) continue;
          double fill = area / static_cast<double>(bound.width * bound.height);
          if (fill < 0.55) continue;

          cv::Point2f center(
            static_cast<float>(safe_zone.x + bound.x + bound.width / 2.0),
            static_cast<float>(safe_zone.y + bound.y + bound.height / 2.0)
          );

          const double dist = cv::norm(center - target) / std::max(1.0, zone_diag);
          const double area_bonus = std::min(area, 2500.0) / 2500.0 * 0.08;
          const double square_penalty = std::abs(std::log(ar)) * 0.05;
          const double score = dist + square_penalty - area_bonus;
          if (score < best_score) {
            best_score = score;
            best = center;
            found = true;
          }
        }

        // If we already have an acceptable marker, skip the remaining thresholds.
        // Empirical score range on real data: 0.30-0.50 for genuine markers
        // (their centres sit ~30-50% of the corner zone diagonal from the
        // target, so `dist` alone contributes ~0.4). Threshold 0.50 keeps the
        // door open for marginally better candidates while still saving ~50%
        // of the per-corner cost when threshold 1 already finds the marker.
        if (found && best_score < 0.50) break;
      }

      if (found) out = best;
      return found;
    };

    if (markers.size() < 4) {
      cv::Point2f tl, tr, bl, br;
      const bool ok_tl = findLocalCornerMarker(cv::Rect(0, 120, 520, 560), cv::Point2f(0.0f, 0.0f), tl);
      const bool ok_tr = findLocalCornerMarker(cv::Rect(1180, 120, 520, 560), cv::Point2f(static_cast<float>(kBaseWidth), 0.0f), tr);
      const bool ok_bl = findLocalCornerMarker(cv::Rect(0, 1780, 560, 620), cv::Point2f(0.0f, static_cast<float>(kBaseHeight)), bl);
      const bool ok_br = findLocalCornerMarker(cv::Rect(1140, 1780, 560, 620), cv::Point2f(static_cast<float>(kBaseWidth), static_cast<float>(kBaseHeight)), br);

      if (ok_tl && ok_tr && ok_bl && ok_br) {
        markers = {tl, tr, bl, br};
        printf("[Stage2MarkerCrop] Local corner markers: TL(%.1f,%.1f) TR(%.1f,%.1f) BR(%.1f,%.1f) BL(%.1f,%.1f)\n",
               tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y);
      } else {
        printf("[Stage2MarkerCrop] Local marker fallback incomplete: TL=%d TR=%d BR=%d BL=%d\n",
               ok_tl ? 1 : 0, ok_tr ? 1 : 0, ok_br ? 1 : 0, ok_bl ? 1 : 0);
      }
    }

    if (markers.size() >= 4) {
      // Sort by y then x to get [TL, TR, BR, BL]
      std::sort(markers.begin(), markers.end(), [](const cv::Point2f& a, const cv::Point2f& b){
        return a.y < b.y;
      });
      std::vector<cv::Point2f> top_row = {markers[0], markers[1]};
      if (top_row[0].x > top_row[1].x) std::swap(top_row[0], top_row[1]);
      std::vector<cv::Point2f> bot_row = {markers[markers.size()-2], markers[markers.size()-1]};
      if (bot_row[0].x > bot_row[1].x) std::swap(bot_row[0], bot_row[1]);

      cv::Point2f tl = top_row[0], tr = top_row[1], br = bot_row[1], bl = bot_row[0];

      // Compute maxWidth/maxHeight (notebook's approach)
      double widthA  = std::sqrt(std::pow(br.x-bl.x,2)+std::pow(br.y-bl.y,2));
      double widthB  = std::sqrt(std::pow(tr.x-tl.x,2)+std::pow(tr.y-tl.y,2));
      double heightA = std::sqrt(std::pow(tr.x-br.x,2)+std::pow(tr.y-br.y,2));
      double heightB = std::sqrt(std::pow(tl.x-bl.x,2)+std::pow(tl.y-bl.y,2));
      int maxW = std::max((int)widthA, (int)widthB);
      int maxH = std::max((int)heightA, (int)heightB);
      if (maxW > 100 && maxH > 100) {
        std::vector<cv::Point2f> src_pts = {tl, tr, br, bl};
        std::vector<cv::Point2f> dst_pts = {
          {0.0f, 0.0f}, {(float)(maxW-1), 0.0f},
          {(float)(maxW-1), (float)(maxH-1)}, {0.0f, (float)(maxH-1)}
        };
        cv::Mat M = cv::getPerspectiveTransform(src_pts, dst_pts);
        cv::Mat cropped;
        cv::warpPerspective(paper, cropped, M, cv::Size(maxW, maxH));
        cv::Mat final_std;
        cv::resize(cropped, final_std, cv::Size(kBaseWidth, kBaseHeight));
        std::memcpy(s_normbuf.data(), final_std.data, static_cast<std::size_t>(base_bytes));
      }
    }
  }

  // Save color warped image AFTER Stage 2 crop so preview = exactly what Stage 3 analyzes
  g_last_warped.assign(s_normbuf.begin(), s_normbuf.begin() + base_bytes);

  // 3. OpenCV Blur + Adaptive Threshold (on tightly cropped 1700x2400)
  cv::Mat paper_rgba(kBaseHeight, kBaseWidth, CV_8UC4, (void*)s_normbuf.data());
  cv::Mat gray, blurred, binary;
  cv::cvtColor(paper_rgba, gray, cv::COLOR_RGBA2GRAY);
  cv::GaussianBlur(gray, blurred, cv::Size(5, 5), 0);
  cv::adaptiveThreshold(blurred, binary, 255, cv::ADAPTIVE_THRESH_MEAN_C, cv::THRESH_BINARY_INV, 31, 5);

  // Set up visualization preview buffer exactly like Python (Color image overlaid)
  cv::Mat display = paper_rgba.clone();

  // 3. Grid Analysis (Stage 3 — Fixed BLOCKS matching app.js for deterministic detection)
  //    After Stage 1+2 warp to canonical 1700x2400, bubble positions are fixed.

  // Fixed block definitions matching app.js BLOCKS (in 1700x2400 space)
  struct Block {
    const char* name;
    int q_offset;
    int cols;
    int rows;
    int x1, y1, x2, y2;
  };

  const Block blocks[] = {
    {"MSSV", -1, 6,  10, 78,  860, 456, 1530},   // MSSV region (6 cols × 10 rows, digits 0-9)
    {"KEY",  -2, 3,  10, 519, 860, 711, 1530},   // KEY region (3 cols × 10 rows, digits 0-9)
    {"Q1",    0, 5,  10, 880, 860, 1183, 1530},  // Q1-Q10
    {"Q2",   10, 5,  10, 1249, 856, 1563, 1527}, // Q11-Q20
    {"Q3",   20, 5,  10, 140, 1604, 447, 2311},  // Q21-Q30
    {"Q4",   30, 5,  10, 515, 1597, 819, 2303},  // Q31-Q40
    {"Q5",   40, 5,  10, 882, 1593, 1194, 2311}, // Q41-Q50
    {"Q6",   50, 5,  10, 1251, 1585, 1568, 2292} // Q51-Q60
  };
  const int num_blocks = sizeof(blocks) / sizeof(blocks[0]);

  // Fixed bubble radius for density sampling (in 1700x2400 space)
  const int R = 18;

  // Precompute circle sampling row bounds. Replaces the old
  // cv::Mat::zeros + cv::circle + cv::bitwise_and + cv::countNonZero pattern
  // (which allocated a 1700x2400 mask per bubble — ~3GB churn per sheet).
  std::vector<int> circle_dx(2 * R + 1);
  for (int dy = -R; dy <= R; ++dy) {
    circle_dx[dy + R] = static_cast<int>(std::sqrt(static_cast<double>(R * R - dy * dy)));
  }

  auto countCircle = [&](const cv::Mat& img, int cx, int cy) -> int {
    const int W = img.cols, H = img.rows;
    int sum = 0;
    for (int dy = -R; dy <= R; ++dy) {
      const int yy = cy + dy;
      if (yy < 0 || yy >= H) continue;
      const int dx_max = circle_dx[dy + R];
      const int x0 = std::max(0, cx - dx_max);
      const int x1 = std::min(W - 1, cx + dx_max);
      const std::uint8_t* row = img.ptr<std::uint8_t>(yy);
      for (int xx = x0; xx <= x1; ++xx) {
        if (row[xx]) ++sum;
      }
    }
    return sum;
  };

  auto paintInkGreen = [&](cv::Mat& dst, const cv::Mat& bin, int cx, int cy) {
    const int W = dst.cols, H = dst.rows;
    for (int dy = -R; dy <= R; ++dy) {
      const int yy = cy + dy;
      if (yy < 0 || yy >= H) continue;
      const int dx_max = circle_dx[dy + R];
      const int x0 = std::max(0, cx - dx_max);
      const int x1 = std::min(W - 1, cx + dx_max);
      const std::uint8_t* binRow = bin.ptr<std::uint8_t>(yy);
      std::uint8_t* dstRow = dst.ptr<std::uint8_t>(yy);
      for (int xx = x0; xx <= x1; ++xx) {
        if (binRow[xx]) {
          const int i = xx * 4;
          dstRow[i + 0] = 0;
          dstRow[i + 1] = 255;
          dstRow[i + 2] = 0;
          dstRow[i + 3] = 255;
        }
      }
    }
  };

  std::vector<RegionData> regions_data;
  std::vector<double> all_grid_densities;

  for (int bi = 0; bi < num_blocks; ++bi) {
      const Block& blk = blocks[bi];

      // Compute cell centers using floating-point to avoid integer truncation drift
      float cellW = (float)(blk.x2 - blk.x1) / blk.cols;
      float cellH = (float)(blk.y2 - blk.y1) / blk.rows;

      std::vector<int> expected_x(blk.cols);
      for (int c = 0; c < blk.cols; c++)
          expected_x[c] = static_cast<int>(std::round(blk.x1 + (c + 0.5f) * cellW));

      std::vector<int> expected_y(blk.rows);
      for (int r = 0; r < blk.rows; r++)
          expected_y[r] = static_cast<int>(std::round(blk.y1 + (r + 0.5f) * cellH));

      // Sample density at each fixed cell center — direct pixel scan
      // within the circle's bounding box (no per-bubble Mat allocation).
      std::vector<Bubble> region_bubbles;
      const double circle_area = CV_PI * R * R;
      for (int cy : expected_y) {
          for (int cx : expected_x) {
              const double density = countCircle(binary, cx, cy) / circle_area;
              region_bubbles.push_back(Bubble{cx, cy, density});
              all_grid_densities.push_back(density);
          }
      }

      regions_data.push_back({blk.name, blk.q_offset, expected_x, expected_y, R, region_bubbles});
  }

  double global_avg_density = 0.5;
  std::vector<double> confident_fills;
  for (double d : all_grid_densities) { if (d > 0.35) confident_fills.push_back(d); }
  if (!confident_fills.empty()) {
      double sum = 0;
      for (double d : confident_fills) sum += d;
      global_avg_density = sum / confident_fills.size();
  }

  std::vector<double> recent_history;
  out.mssv_valid = 1; out.key_valid = 1;
  out.answer_masks.fill(0);
  out.suspicious.fill(0);
  out.bubble_densities.fill(0);

  for (const auto& region : regions_data) {
      if (region.name == "MSSV" || region.name == "KEY") {
          for (size_t col_idx = 0; col_idx < region.expected_x.size(); col_idx++) {
              int cx = region.expected_x[col_idx];
              std::vector<Bubble> col_b;
              for (const auto& b : region.bubbles) if (b.cx == cx) col_b.push_back(b);
              if (col_b.empty()) continue;

              for (const auto& b : col_b) {
                  cv::circle(display, cv::Point(b.cx, b.cy), region.R, cv::Scalar(0, 0, 255, 255), 2);
              }

              std::sort(col_b.begin(), col_b.end(), [](const Bubble& a, const Bubble& b){ return a.density > b.density; });
              auto best = col_b[0];

              if (best.density > 0.20 && best.density >= global_avg_density * 0.40) {
                  // Color it green
                  paintInkGreen(display, binary, best.cx, best.cy);

                  int best_row = -1;
                  for (int r = 0; r < 10; r++) { if (region.expected_y[r] == best.cy) { best_row = r; break; } }
                  
                  if (region.name == "MSSV") out.mssv_digits[col_idx] = best_row;
                  if (region.name == "KEY")  out.key_digits[col_idx] = best_row;
              } else {
                  if (region.name == "MSSV") { out.mssv_valid = 0; out.mssv_digits[col_idx] = -1; }
                  if (region.name == "KEY")  { out.key_valid = 0; out.key_digits[col_idx] = -1; }
              }
          }
      } else {
          // Question Blocks
          for (int r = 0; r < 10; r++) {
              int cy = region.expected_y[r];
              std::vector<Bubble> row_b;
              for (const auto& b : region.bubbles) if (b.cy == cy) row_b.push_back(b);
              if (row_b.empty()) continue;

              for (const auto& b : row_b) {
                  cv::circle(display, cv::Point(b.cx, b.cy), region.R, cv::Scalar(0, 0, 255, 255), 2);
              }

              double max_d = 0;
              for (const auto& b : row_b) if (b.density > max_d) max_d = b.density;

              double ref_density = global_avg_density;
              if (!recent_history.empty()) {
                  double sum = 0; for (double d : recent_history) sum += d;
                  ref_density = sum / recent_history.size();
              }

              int q_index = region.q_offset + r;
              
              // Record raw densities upfront for benchmarking (before any gating)
              for (size_t col_idx = 0; col_idx < region.expected_x.size(); col_idx++) {
                  int cx = region.expected_x[col_idx];
                  auto it = std::find_if(row_b.begin(), row_b.end(), [cx](const Bubble& b){ return b.cx==cx; });
                  if (it != row_b.end() && q_index >= 0 && q_index < 60 && col_idx < 5) {
                      out.bubble_densities[q_index * 5 + col_idx] = static_cast<int>(it->density * 10000.0);
                  }
              }

              // ── Bubble-fill decision rule ──────────────────────────────────
              // USE_ROW_ZSCORE=1  → Rule E: per-row z-score + absolute floor 0.15.
              //                    Robust to per-sheet pencil-darkness variation.
              //                    Measured +70% answered on faint-pencil dataset,
              //                    +13% on legacy dataset, multi-mark dropped 1.3→0.7
              //                    (less over-detection — matches paper §V.A claim
              //                    that CV "tends to over-detect").
              // USE_ROW_ZSCORE=0  → Legacy gate: max_d≥ref_density*0.55 + 0.20 floor.
              //
              // To roll back: set USE_ROW_ZSCORE 0 (and rebuild) or simply
              //               `cp web/wasm/omr.wasm.before-rule-e.bak web/wasm/omr.wasm`.
#define USE_ROW_ZSCORE 1
#if USE_ROW_ZSCORE
              // Compute per-row mean and std-dev of the 5 bubble densities.
              double row_mean = 0.0;
              for (const auto& b : row_b) row_mean += b.density;
              row_mean /= static_cast<double>(row_b.size());
              double row_var = 0.0;
              for (const auto& b : row_b) {
                  const double d = b.density - row_mean;
                  row_var += d * d;
              }
              const double row_std = std::sqrt(row_var / static_cast<double>(row_b.size()));

              if (max_d < 0.15) {
                  continue;  // all 5 bubbles below absolute floor → row empty
              }

              int mask = 0;
              int filled_count = 0;

              for (size_t col_idx = 0; col_idx < region.expected_x.size(); col_idx++) {
                  int cx = region.expected_x[col_idx];
                  auto it = std::find_if(row_b.begin(), row_b.end(), [cx](const Bubble& b){ return b.cx==cx; });
                  if (it != row_b.end()) {
                      // Filled if bubble stands out from its 4 neighbours by >1σ
                      // AND passes absolute floor (guards against noise spikes).
                      if (it->density > 0.15 && it->density > row_mean + 1.0 * row_std) {
                          paintInkGreen(display, binary, it->cx, it->cy);
                          mask |= (1 << col_idx);
                          filled_count++;
                      }
                  }
              }
#else
              if (max_d < 0.20 || max_d < ref_density * 0.55) {
                  continue;
              }

              int mask = 0;
              int filled_count = 0;

              for (size_t col_idx = 0; col_idx < region.expected_x.size(); col_idx++) {
                  int cx = region.expected_x[col_idx];
                  auto it = std::find_if(row_b.begin(), row_b.end(), [cx](const Bubble& b){ return b.cx==cx; });
                  if (it != row_b.end()) {
                      if (it->density > 0.20 && it->density >= max_d * 0.70 && it->density >= ref_density * 0.55) {
                          paintInkGreen(display, binary, it->cx, it->cy);
                          mask |= (1 << col_idx);
                          filled_count++;
                      }
                  }
              }
#endif

              if (q_index < 60) {
                  out.answer_masks[q_index] = mask;
                  if (filled_count > 1) out.suspicious[q_index] = 1;
              }

              if (max_d > global_avg_density * 0.60) {
                  recent_history.push_back(max_d);
                  if (recent_history.size() > 5) recent_history.erase(recent_history.begin());
              }
          }
      }
  }

  // Write processed preview buffer directly
  std::memcpy(s_normbuf.data(), display.data, base_bytes);

  // Return to JS caller via out ptr (if image dimension matches)
  if (width == kBaseWidth && height == kBaseHeight) {
      std::memcpy(rgba, s_normbuf.data(), base_bytes);
  }
  g_last_preview.assign(s_normbuf.begin(), s_normbuf.begin() + base_bytes);

  out.status = 0;
  return out;
}

int OmrCore::CopyLastPreview(std::uint8_t* dst, int dst_len) const {
  constexpr int kPreviewBytes = 1700 * 2400 * 4;
  if (!dst || dst_len < kPreviewBytes) return -1;
  if (static_cast<int>(g_last_preview.size()) < kPreviewBytes) return -1;
  std::memcpy(dst, g_last_preview.data(), static_cast<std::size_t>(kPreviewBytes));
  return kPreviewBytes;
}

int OmrCore::CopyLastWarped(std::uint8_t* dst, int dst_len) const {
  constexpr int kPreviewBytes = 1700 * 2400 * 4;
  if (!dst || dst_len < kPreviewBytes) return -1;
  if (static_cast<int>(g_last_warped.size()) < kPreviewBytes) return -1;
  std::memcpy(dst, g_last_warped.data(), static_cast<std::size_t>(kPreviewBytes));
  return kPreviewBytes;
}

void OmrCore::ClearPreviews() {
  std::vector<std::uint8_t>().swap(g_last_preview);
  std::vector<std::uint8_t>().swap(g_last_warped);
}

}  // namespace omr
