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
struct SearchArea {
    std::string name;
    int cols;
    int q_offset; // Which question index to start this block from
    cv::Rect rect;
};

struct Bubble {
    int cx;
    int cy;
    double density;
    cv::Mat ink;
};

struct RegionData {
    std::string name;
    int q_offset;
    cv::Rect rect;
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

    // Collect valid markers: area 1500-15000, aspect 0.8-1.2, fill > 0.7
    // Additionally restrict to outer 20% zone of each side to avoid confusion with
    // filled bubbles in the interior of the form.
    const int zone_w = kBaseWidth  / 5;  // 340px
    const int zone_h = kBaseHeight / 5;  // 480px
    std::vector<cv::Point2f> markers;
    for (const auto& c : ctrs) {
      cv::Rect bound = cv::boundingRect(c);
      double area = cv::contourArea(c);
      double ar = (double)bound.width / bound.height;
      if (area < 1500 || area > 15000) continue;
      if (ar < 0.8 || ar > 1.2) continue;
      if ((area / (double)(bound.width * bound.height)) < 0.7) continue;
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

  // 3. Grid Analysis (Stage 3 Notebook Logic)
  int ax[] = {481, 851, 1220};
  int ay[] = {830, 1571, 2315};
  int y_top_range[] = {ay[0] + 30, ay[1] - 40};
  int y_bot_range[] = {ay[1] + 30, ay[2] - 40};

  std::vector<SearchArea> search_areas = {
      {"MSSV", 6, 0,  cv::Rect(ax[0]-440, y_top_range[0], 430, y_top_range[1]-y_top_range[0])},
      {"KEY",  3, 0,  cv::Rect(ax[0]+15,  y_top_range[0], ax[1]-15-(ax[0]+15), y_top_range[1]-y_top_range[0])},
      {"Q1",   5, 0,  cv::Rect(ax[1]+15,  y_top_range[0], ax[2]-15-(ax[1]+15), y_top_range[1]-y_top_range[0])},
      {"Q2",   5, 10, cv::Rect(ax[2]+15,  y_top_range[0], 425, y_top_range[1]-y_top_range[0])},
      {"Q3",   5, 20, cv::Rect(ax[0]-440, y_bot_range[0], 430, y_bot_range[1]-y_bot_range[0])},
      {"Q4",   5, 30, cv::Rect(ax[0]+15,  y_bot_range[0], ax[1]-15-(ax[0]+15), y_bot_range[1]-y_bot_range[0])},
      {"Q5",   5, 40, cv::Rect(ax[1]+15,  y_bot_range[0], ax[2]-15-(ax[1]+15), y_bot_range[1]-y_bot_range[0])},
      {"Q6",   5, 50, cv::Rect(ax[2]+15,  y_bot_range[0], 425, y_bot_range[1]-y_bot_range[0])}
  };

  std::vector<RegionData> regions_data;
  std::vector<double> all_grid_densities;

  auto get_median = [](std::vector<int>& v) -> int {
      if (v.empty()) return 0;
      std::sort(v.begin(), v.end());
      return v[v.size() / 2];
  };

  for (const auto& sa : search_areas) {
      cv::Mat roi_bin = binary(sa.rect);
      
      std::vector<std::vector<cv::Point>> contours;
      cv::findContours(roi_bin, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
      
      struct ValidB { cv::Point center; int radius; };
      std::vector<ValidB> valid_bubbles;

      for (const auto& cnt : contours) {
          cv::Rect bound = cv::boundingRect(cnt);
          // Relaxed range: 15-80px to handle both pencil marks and printed ink circles
          if (bound.width >= 15 && bound.width <= 80 && bound.height >= 15 && bound.height <= 80) {
              double ar = (double)bound.width / bound.height;
              if (ar >= 0.5 && ar <= 2.0) {
                  double area_val = cv::contourArea(cnt);
                  double peri = cv::arcLength(cnt, true);
                  // Lowered circularity: 0.2 instead of 0.4 (printed circles have irregular edges from scan)
                  double circularity = peri > 0 ? (4 * CV_PI * area_val / (peri * peri)) : 0;
                  if (circularity > 0.2) {
                      valid_bubbles.push_back({
                          cv::Point(bound.x + bound.width / 2, bound.y + bound.height / 2),
                          std::max(bound.width, bound.height) / 2
                      });
                  }
              }
          }
      }

      // Lowered minimum: 3 instead of 5, allowing sparse answer key regions to pass
      if (valid_bubbles.size() < 3) continue;

      std::vector<int> valid_x, valid_y;
      for (const auto& b : valid_bubbles) {
          valid_x.push_back(b.center.x);
          valid_y.push_back(b.center.y);
      }
      std::sort(valid_x.begin(), valid_x.end());
      std::sort(valid_y.begin(), valid_y.end());

      int col_size = std::max(1, (int)valid_x.size() / sa.cols);
      int row_size = std::max(1, (int)valid_y.size() / 10);

      std::vector<int> min_x_group(valid_x.begin(), valid_x.begin() + col_size);
      std::vector<int> max_x_group(valid_x.end() - col_size, valid_x.end());
      int min_x = get_median(min_x_group);
      int max_x = get_median(max_x_group);

      std::vector<int> min_y_group(valid_y.begin(), valid_y.begin() + row_size);
      std::vector<int> max_y_group(valid_y.end() - row_size, valid_y.end());
      int min_y = get_median(min_y_group);
      int max_y = get_median(max_y_group);

      double step_x = sa.cols > 1 ? (double)(max_x - min_x) / (sa.cols - 1) : 0;
      double step_y = (double)(max_y - min_y) / 9.0;

      std::vector<int> expected_x(sa.cols);
      for (int i = 0; i < sa.cols; i++) expected_x[i] = min_x + (int)(i * step_x);

      std::vector<int> expected_y(10);
      for (int i = 0; i < 10; i++) expected_y[i] = min_y + (int)(i * step_y);

      std::vector<int> radii;
      for (const auto& b : valid_bubbles) radii.push_back(b.radius);
      int R = get_median(radii);

      std::vector<Bubble> region_bubbles;
      for (int cy : expected_y) {
          for (int cx : expected_x) {
              cv::Mat mask = cv::Mat::zeros(roi_bin.size(), CV_8UC1);
              cv::circle(mask, cv::Point(cx, cy), R - 2, cv::Scalar(255), -1);
              cv::Mat ink_pixels;
              cv::bitwise_and(roi_bin, roi_bin, ink_pixels, mask);
              double density = cv::countNonZero(ink_pixels) / (CV_PI * R * R);
              region_bubbles.push_back({cx, cy, density, ink_pixels});
              all_grid_densities.push_back(density);
          }
      }

      regions_data.push_back({sa.name, sa.q_offset, sa.rect, expected_x, expected_y, R, region_bubbles});
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

  for (const auto& region : regions_data) {
      if (region.name == "MSSV" || region.name == "KEY") {
          for (size_t col_idx = 0; col_idx < region.expected_x.size(); col_idx++) {
              int cx = region.expected_x[col_idx];
              std::vector<Bubble> col_b;
              for (const auto& b : region.bubbles) if (b.cx == cx) col_b.push_back(b);
              if (col_b.empty()) continue;

              for (const auto& b : col_b) {
                  cv::circle(display(region.rect), cv::Point(b.cx, b.cy), region.R, cv::Scalar(0, 0, 255, 255), 2);
              }

              std::sort(col_b.begin(), col_b.end(), [](const Bubble& a, const Bubble& b){ return a.density > b.density; });
              auto best = col_b[0];

              if (best.density > 0.20 && best.density >= global_avg_density * 0.40) {
                  // Color it green
                  display(region.rect).setTo(cv::Scalar(0, 255, 0, 255), best.ink > 0);
                  
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
                  cv::circle(display(region.rect), cv::Point(b.cx, b.cy), region.R, cv::Scalar(0, 0, 255, 255), 2);
              }

              double max_d = 0;
              for (const auto& b : row_b) if (b.density > max_d) max_d = b.density;

              double ref_density = global_avg_density;
              if (!recent_history.empty()) {
                  double sum = 0; for (double d : recent_history) sum += d;
                  ref_density = sum / recent_history.size();
              }

              if (max_d < 0.20 || max_d < ref_density * 0.55) {
                  continue;
              }

              int q_index = region.q_offset + r;
              int mask = 0;
              int filled_count = 0;

              for (size_t col_idx = 0; col_idx < region.expected_x.size(); col_idx++) {
                  int cx = region.expected_x[col_idx];
                  auto it = std::find_if(row_b.begin(), row_b.end(), [cx](const Bubble& b){ return b.cx==cx; });
                  if (it != row_b.end()) {
                      if (it->density > 0.20 && it->density >= max_d * 0.70 && it->density >= ref_density * 0.55) {
                          display(region.rect).setTo(cv::Scalar(0, 255, 0, 255), it->ink > 0);
                          mask |= (1 << col_idx);
                          filled_count++;
                      }
                  }
              }

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

}  // namespace omr
