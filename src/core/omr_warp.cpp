#include "core/omr_warp.h"
#include <opencv2/opencv.hpp>
#include <vector>
#include <algorithm>
#include <iostream>
#include <cmath>

namespace omr {

constexpr int STD_W = 1700;
constexpr int STD_H = 2400;

void ResizeRgba(const std::uint8_t* src, int sw, int sh,
                std::uint8_t* dst, int dw, int dh) {
    cv::Mat srcMat(sh, sw, CV_8UC4, (void*)src);
    cv::Mat dstMat(dh, dw, CV_8UC4, (void*)dst);
    cv::resize(srcMat, dstMat, cv::Size(dw, dh), 0, 0, cv::INTER_NEAREST);
}

std::vector<cv::Point2f> order_points(const std::vector<cv::Point2f>& pts) {
    std::vector<cv::Point2f> rect(4);
    std::vector<float> sum(4), diff(4);
    for (int i = 0; i < 4; i++) {
        sum[i] = pts[i].x + pts[i].y;
        diff[i] = pts[i].x - pts[i].y;
    }
    rect[0] = pts[std::distance(sum.begin(), std::min_element(sum.begin(), sum.end()))];
    rect[2] = pts[std::distance(sum.begin(), std::max_element(sum.begin(), sum.end()))];
    rect[1] = pts[std::distance(diff.begin(), std::min_element(diff.begin(), diff.end()))];
    rect[3] = pts[std::distance(diff.begin(), std::max_element(diff.begin(), diff.end()))];
    return rect;
}

std::vector<cv::Point2f> get_four_corners(std::vector<cv::Point2f> pts) {
    if (pts.size() < 4) return pts;
    std::sort(pts.begin(), pts.end(), [](const cv::Point2f& a, const cv::Point2f& b) {
        return a.y < b.y;
    });
    std::vector<cv::Point2f> top_row = {pts[0], pts[1]};
    if (top_row[0].x > top_row[1].x) std::swap(top_row[0], top_row[1]);

    std::vector<cv::Point2f> bottom_row = {pts[pts.size()-2], pts[pts.size()-1]};
    if (bottom_row[0].x > bottom_row[1].x) std::swap(bottom_row[0], bottom_row[1]);

    return {top_row[0], top_row[1], bottom_row[1], bottom_row[0]};
}

bool NormalizeSheet(const std::uint8_t* src_rgba, int src_w, int src_h,
                    std::uint8_t* dst_rgba) {
    if (!src_rgba || src_w <= 0 || src_h <= 0 || !dst_rgba) return false;

    cv::Mat img(src_h, src_w, CV_8UC4, (void*)src_rgba);
    cv::Mat gray, blur, edged;

    // Stage 1: Extract Paper boundary via OpenCV
    cv::cvtColor(img, gray, cv::COLOR_RGBA2GRAY);
    cv::GaussianBlur(gray, blur, cv::Size(5, 5), 0);
    cv::Canny(blur, edged, 75, 200);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(edged, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

    std::sort(contours.begin(), contours.end(), [](const std::vector<cv::Point>& a, const std::vector<cv::Point>& b) {
        return cv::contourArea(a) > cv::contourArea(b);
    });

    std::vector<cv::Point> paper_contour;
    bool found_paper = false;
    for (size_t i = 0; i < std::min((size_t)5, contours.size()); i++) {
        double peri = cv::arcLength(contours[i], true);
        std::vector<cv::Point> approx;
        cv::approxPolyDP(contours[i], approx, 0.02 * peri, true);
        if (approx.size() == 4) {
            paper_contour = approx;
            found_paper = true;
            break;
        }
    }

    cv::Mat paper_only;
    if (found_paper) {
        std::vector<cv::Point2f> pts(4);
        for(int i=0; i<4; i++) pts[i] = paper_contour[i];
        std::vector<cv::Point2f> rect = order_points(pts);
        
        std::vector<cv::Point2f> dst = {
            cv::Point2f(0, 0),
            cv::Point2f(STD_W - 1, 0),
            cv::Point2f(STD_W - 1, STD_H - 1),
            cv::Point2f(0, STD_H - 1)
        };
        cv::Mat M = cv::getPerspectiveTransform(rect, dst);
        cv::warpPerspective(img, paper_only, M, cv::Size(STD_W, STD_H));
    } else {
        // Fallback: If paper contour not found, consider entire image as roughly cropped paper.
        cv::resize(img, paper_only, cv::Size(STD_W, STD_H));
    }

    // Stage 2: Marker detection and tighter crop
    cv::Mat gray2, thresh, cleaned;
    cv::cvtColor(paper_only, gray2, cv::COLOR_RGBA2GRAY);
    cv::threshold(gray2, thresh, 0, 255, cv::THRESH_BINARY_INV | cv::THRESH_OTSU);
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(5, 5));
    cv::morphologyEx(thresh, cleaned, cv::MORPH_OPEN, kernel);

    std::vector<std::vector<cv::Point>> contours_markers;
    cv::findContours(cleaned, contours_markers, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

    std::vector<cv::Point2f> markers;
    for (const auto& c : contours_markers) {
        cv::Rect bound = cv::boundingRect(c);
        double area = cv::contourArea(c);
        double aspect_ratio = (double)bound.width / bound.height;
        if (area > 1500 && area < 15000 && aspect_ratio >= 0.8 && aspect_ratio <= 1.2) {
            if ((area / (bound.width * bound.height)) > 0.7) {
                float cx = bound.x + bound.width / 2.0f;
                float cy = bound.y + bound.height / 2.0f;
                markers.push_back(cv::Point2f(cx, cy));
            }
        }
    }

    cv::Mat final_std;
    if (markers.size() >= 4) {
        std::vector<cv::Point2f> rect = get_four_corners(markers);
        cv::Point2f tl = rect[0], tr = rect[1], br = rect[2], bl = rect[3];
        
        double widthA = std::sqrt(std::pow(br.x - bl.x, 2) + std::pow(br.y - bl.y, 2));
        double widthB = std::sqrt(std::pow(tr.x - tl.x, 2) + std::pow(tr.y - tl.y, 2));
        int maxWidth = std::max((int)widthA, (int)widthB);
        
        double heightA = std::sqrt(std::pow(tr.x - br.x, 2) + std::pow(tr.y - br.y, 2));
        double heightB = std::sqrt(std::pow(tl.x - bl.x, 2) + std::pow(tl.y - bl.y, 2));
        int maxHeight = std::max((int)heightA, (int)heightB);
        
        std::vector<cv::Point2f> dst = {
            cv::Point2f(0, 0),
            cv::Point2f(maxWidth - 1, 0),
            cv::Point2f(maxWidth - 1, maxHeight - 1),
            cv::Point2f(0, maxHeight - 1)
        };
        cv::Mat M_crop = cv::getPerspectiveTransform(rect, dst);
        cv::Mat cropped_img;
        cv::warpPerspective(paper_only, cropped_img, M_crop, cv::Size(maxWidth, maxHeight));
        
        cv::resize(cropped_img, final_std, cv::Size(STD_W, STD_H));
    } else {
        final_std = paper_only;
    }

    cv::Mat final_dest(STD_H, STD_W, CV_8UC4, (void*)dst_rgba);
    if(final_std.size() != cv::Size(STD_W, STD_H)) {
        cv::resize(final_std, final_dest, cv::Size(STD_W, STD_H));
    } else {
        final_std.copyTo(final_dest);
    }
    
    return true;
}

}  // namespace omr
