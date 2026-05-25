"""
paper_detection_reference.py

Exact 1:1 Python reproduction of NormalizeSheet() from omr_warp.cpp.
Uses cv2 (OpenCV-Python 4.x) and numpy only — no other libraries.

C++ source: src/core/omr_warp.cpp
"""

import os
import cv2
import numpy as np
from typing import Optional, Tuple


# ─── Constants (omr_warp.cpp:15-16, 644) ─────────────────────────────────────
K_TARGET_W  = 1700   # omr_warp.cpp:15 kTargetW
K_TARGET_H  = 2400   # omr_warp.cpp:16 kTargetH
K_ANALYSIS_W = 800   # omr_warp.cpp:644 kAnalysisW (fixed; height is proportional)


# ═══════════════════════════════════════════════════════════════
# 1. IMAGE CONVERSION HELPERS
# ═══════════════════════════════════════════════════════════════

def rgba_to_gray(rgba: np.ndarray) -> np.ndarray:
    """
    omr_warp.cpp:31-39  RgbaToGray — BT.601 integer arithmetic.
    gray[i] = (299*r + 587*g + 114*b) / 1000  [C++ integer truncation = floor for >=0]

    IMPORTANT: cv2.COLOR_RGBA2GRAY uses a float formula and rounds differently.
    This exact integer version is required to reproduce the C++ Otsu threshold.
    """
    r = rgba[:, :, 0].astype(np.int32)
    g = rgba[:, :, 1].astype(np.int32)
    b = rgba[:, :, 2].astype(np.int32)
    # omr_warp.cpp:37: (299*r + 587*g + 114*b) / 1000  — integer division
    return ((299 * r + 587 * g + 114 * b) // 1000).astype(np.uint8)


# ═══════════════════════════════════════════════════════════════
# 2. GAUSSIAN BLUR 5×5  (omr_warp.cpp:42-69)
# ═══════════════════════════════════════════════════════════════

def gaussian_blur5(gray: np.ndarray) -> np.ndarray:
    """
    omr_warp.cpp:42-69  GaussianBlur5 — separable [1,4,6,4,1]/16 kernel.

    Key differences from cv2.GaussianBlur:
    - Border: REPLICATE (clamp to edge value), same as C++ max(0, min(x+k, w-1))
    - Arithmetic: integer floor division by 16 (C++ integer truncation)
      e.g. acc=23 → 23//16=1, NOT round(23/16)=1.4→1 (happens to agree here but
      differs when acc mod 16 >= 8)

    omr_warp.cpp:43:  static constexpr int kW[5] = {1, 4, 6, 4, 1};
    omr_warp.cpp:54:  tmp[y*w+x] = (uint8_t)(acc / 16);   // integer floor
    omr_warp.cpp:66:  dst[y*w+x] = (uint8_t)(acc / 16);
    """
    h, w = gray.shape
    kw = np.array([1, 4, 6, 4, 1], dtype=np.int32)

    # Horizontal pass — pad 2 pixels each side with edge value (BORDER_REPLICATE)
    src_h = np.pad(gray.astype(np.int32), ((0, 0), (2, 2)), mode='edge')
    tmp = (src_h[:, 0:w]*kw[0] + src_h[:, 1:w+1]*kw[1] + src_h[:, 2:w+2]*kw[2] +
           src_h[:, 3:w+3]*kw[3] + src_h[:, 4:w+4]*kw[4]) // 16
    tmp = tmp.astype(np.uint8)

    # Vertical pass
    src_v = np.pad(tmp.astype(np.int32), ((2, 2), (0, 0)), mode='edge')
    dst = (src_v[0:h, :]*kw[0] + src_v[1:h+1, :]*kw[1] + src_v[2:h+2, :]*kw[2] +
           src_v[3:h+3, :]*kw[3] + src_v[4:h+4, :]*kw[4]) // 16
    return dst.astype(np.uint8)


# ═══════════════════════════════════════════════════════════════
# 3. OTSU THRESHOLD  (omr_warp.cpp:89-115)
# ═══════════════════════════════════════════════════════════════

def otsu_threshold(gray: np.ndarray) -> int:
    """
    omr_warp.cpp:89-115  OtsuThreshold — returns threshold value (int).

    Returns the t that maximises between-class variance wB*wF*(mB-mF)^2.
    omr_warp.cpp:98: default best = 128 if no variance peak found.

    NOTE: this returns the RAW threshold value; the caller decides how to apply
    it (BinaryInv for Layer 1, plain Binary for Layer 2).
    """
    n = int(gray.size)
    hist = np.bincount(gray.ravel(), minlength=256).astype(np.float64)
    total = float(n)
    sum_all = float(np.dot(np.arange(256, dtype=np.float64), hist))

    sum_b, w_b = 0.0, 0.0
    max_var = 0.0
    best = 128  # omr_warp.cpp:98

    for t in range(256):
        w_b += hist[t]
        if w_b == 0.0:
            continue
        w_f = total - w_b
        if w_f == 0.0:
            break
        sum_b += t * hist[t]
        m_b = sum_b / w_b
        m_f = (sum_all - sum_b) / w_f
        var = w_b * w_f * (m_b - m_f) ** 2
        if var > max_var:
            max_var = var
            best = t

    return best


# ─── HistogramEqualize (omr_warp.cpp:72-86) ──────────────────────────────────

def histogram_equalize(gray: np.ndarray) -> np.ndarray:
    """
    omr_warp.cpp:72-86  HistogramEqualize.
    omr_warp.cpp:80: lut[i] = (uint8_t)(cdf * 255 / n)  — integer floor division.
    """
    n = int(gray.size)
    hist = np.bincount(gray.ravel(), minlength=256).astype(np.int64)
    cdf  = np.cumsum(hist)
    # omr_warp.cpp:80: integer division
    lut  = (cdf * 255 // n).astype(np.uint8)
    return lut[gray]


# ─── AdaptiveOtsuThreshold (omr_warp.cpp:118-147) ────────────────────────────

def adaptive_otsu_threshold(gray: np.ndarray) -> int:
    """
    omr_warp.cpp:118-147  AdaptiveOtsuThreshold.

    If standard Otsu < 130 (dark image = answer key):
      1. Equalize histogram
      2. Re-run Otsu on equalized image
      3. Scale result back: thresh = (uint8_t)(thresh * 0.6)  [C++ truncation, not round]

    omr_warp.cpp:133: if (thresh < 130) { ... }
    omr_warp.cpp:139: thresh = (uint8_t)(thresh * 0.6);
    """
    thresh      = otsu_threshold(gray)
    thresh_before = thresh
    was_equalized = False

    if thresh < 130:
        was_equalized = True
        equalized = histogram_equalize(gray)
        thresh    = otsu_threshold(equalized)
        # omr_warp.cpp:139: static_cast<uint8_t>(thresh * 0.6) — C++ truncates toward 0
        thresh = int(thresh * 0.6)   # int() truncates same way for positive values

    mean_val = int(gray.mean())
    print(f"[AdaptiveOtsu] Image brightness: mean={mean_val}, "
          f"Otsu={thresh_before} -> {thresh}"
          + (" (equalized)" if was_equalized else ""))
    return thresh


# ═══════════════════════════════════════════════════════════════
# 4. MORPHOLOGY  (omr_warp.cpp:157-201)
# ═══════════════════════════════════════════════════════════════

_K3 = np.ones((3, 3), dtype=np.uint8)  # shared 3×3 kernel


def erode3(binary: np.ndarray) -> np.ndarray:
    """
    omr_warp.cpp:158-172  Erode3 — 3×3 erosion, BORDER_REPLICATE.
    A pixel becomes 0 if ANY 3×3 neighbour is 0.
    C++ border: yy = max(0, min(y+dy, h-1)), same as BORDER_REPLICATE.
    """
    return cv2.erode(binary, _K3, iterations=1, borderType=cv2.BORDER_REPLICATE)


def dilate3(binary: np.ndarray) -> np.ndarray:
    """
    omr_warp.cpp:175-189  Dilate3 — 3×3 dilation, BORDER_REPLICATE.
    A pixel becomes 255 if ANY 3×3 neighbour is 255.
    """
    return cv2.dilate(binary, _K3, iterations=1, borderType=cv2.BORDER_REPLICATE)


def morph_open(binary: np.ndarray) -> np.ndarray:
    """omr_warp.cpp:192-195  MorphOpen = Erode3 then Dilate3 (removes noise)."""
    return dilate3(erode3(binary))


def morph_close(binary: np.ndarray) -> np.ndarray:
    """omr_warp.cpp:198-201  MorphClose = Dilate3 then Erode3 (fills holes)."""
    return erode3(dilate3(binary))


# ═══════════════════════════════════════════════════════════════
# 5. CONNECTED COMPONENTS / BLOB FINDER  (omr_warp.cpp:218-299)
# ═══════════════════════════════════════════════════════════════

def find_blobs(
    binary: np.ndarray,
    gray_src: Optional[np.ndarray],
    min_area: int,
    max_area: int,
) -> list:
    """
    omr_warp.cpp:218-299  FindBlobs — 4-connected components with area filter.

    C++ uses a 2-pass union-find scanning only UP and LEFT neighbours,
    which is exactly 4-connectivity. cv2.connectedComponentsWithStats(conn=4)
    is equivalent.

    CRITICAL: mean_gray is computed over ALL pixels in the bounding box
    (not just blob pixels). omr_warp.cpp:282-287:
      for yy in y1..y2: for xx in x1..x2: gsum += gray_src[yy*w+xx]
    This means a solid dark square next to a bright border has higher mean_gray
    than the dark fill alone — that is the C++ intention.

    Returns list of dicts: {cx, cy, x1, y1, x2, y2, pixel_count, mean_gray}.
    """
    num_labels, _labels, stats, centroids = cv2.connectedComponentsWithStats(
        binary, connectivity=4, ltype=cv2.CV_32S
    )

    blobs = []
    for lbl in range(1, num_labels):            # skip background label 0
        count = int(stats[lbl, cv2.CC_STAT_AREA])
        if count < min_area or count > max_area:
            continue

        x1 = int(stats[lbl, cv2.CC_STAT_LEFT])
        y1 = int(stats[lbl, cv2.CC_STAT_TOP])
        bw = int(stats[lbl, cv2.CC_STAT_WIDTH])
        bh = int(stats[lbl, cv2.CC_STAT_HEIGHT])
        if bw <= 0 or bh <= 0:
            continue
        x2 = x1 + bw - 1
        y2 = y1 + bh - 1

        cx = float(centroids[lbl, 0])   # centroid of blob pixels (matches C++ sx/count)
        cy = float(centroids[lbl, 1])

        # omr_warp.cpp:282-287: mean over ENTIRE bbox (not blob-pixel-only)
        mean_g = 0.0
        if gray_src is not None:
            mean_g = float(gray_src[y1:y2 + 1, x1:x2 + 1].mean())

        blobs.append({
            'cx': cx, 'cy': cy,
            'x1': x1, 'y1': y1, 'x2': x2, 'y2': y2,
            'pixel_count': count,
            'mean_gray': mean_g,
        })

    return blobs


# ═══════════════════════════════════════════════════════════════
# 6. SANITY CHECK  (omr_warp.cpp:437-445)
# ═══════════════════════════════════════════════════════════════

def corners_look_valid(corners: np.ndarray, src_w: int, src_h: int) -> bool:
    """
    omr_warp.cpp:437-445  CornersLookValid.

    Checks minimum 15% span in both X and Y axes:
      corners[1].x - corners[0].x >= src_w * 0.15   (TR.x - TL.x)
      corners[2].x - corners[3].x >= src_w * 0.15   (BR.x - BL.x)
      corners[2].y - corners[1].y >= src_h * 0.15   (BR.y - TR.y)
      corners[3].y - corners[0].y >= src_h * 0.15   (BL.y - TL.y)

    corners layout: [TL, TR, BR, BL] (indices 0-3).
    """
    min_span_x = src_w * 0.15
    min_span_y = src_h * 0.15
    if corners[1, 0] - corners[0, 0] < min_span_x:   # TR.x - TL.x
        return False
    if corners[2, 0] - corners[3, 0] < min_span_x:   # BR.x - BL.x
        return False
    if corners[2, 1] - corners[1, 1] < min_span_y:   # BR.y - TR.y
        return False
    if corners[3, 1] - corners[0, 1] < min_span_y:   # BL.y - TL.y
        return False
    return True


# ═══════════════════════════════════════════════════════════════
# 7. LAYER 1 — MARKER CORNER DETECTION  (omr_warp.cpp:447-552)
# ═══════════════════════════════════════════════════════════════

def find_marker_corners_py(gray: np.ndarray) -> Optional[np.ndarray]:
    """
    omr_warp.cpp:447-552  FindMarkerCorners — LAYER 1.

    Detects 4 black registration squares (solid dark blobs in corners).

    Pipeline:
      1. GaussianBlur5
      2. OtsuThreshold + BinaryINV  (dark marker pixels → 255)
      3. MorphOpen ×2               (removes thin lines, keeps solid squares)
      4. FindBlobs  area ∈ [50, min(n/4, 2000)]
      5. Filter: aspect 0.7-1.4, fill ≥ 0.85, mean_gray < 80
      6. Sort by area desc; keep blobs ≥ 50% of largest (up to 20)
      7. Quadrant assignment by centroid; pick farthest in each quadrant
      8. Must have one blob per quadrant (TL/TR/BR/BL)

    Returns:
        (4, 2) float64 array [TL, TR, BR, BL] in downscaled-image coords.
        None if not enough valid blobs or quadrant assignment fails.
    """
    h, w = gray.shape
    n = w * h

    # omr_warp.cpp:455
    blurred = gaussian_blur5(gray)

    # omr_warp.cpp:457
    thresh = otsu_threshold(blurred)

    # omr_warp.cpp:458  BinaryInv: dark (< thresh) → 255
    binary = np.where(blurred < thresh, np.uint8(255), np.uint8(0))

    # omr_warp.cpp:461-463  MORPH_OPEN ×2
    binary = morph_open(binary)
    binary = morph_open(binary)

    # omr_warp.cpp:467-468  area range: 50 .. min(n/4, 2000)
    max_marker_area = min(n // 4, 2000)
    blobs = find_blobs(binary, gray, min_area=50, max_area=max_marker_area)

    # omr_warp.cpp:476-484  filter: aspect 0.7-1.4, fill ≥ 0.85, mean_gray < 80
    candidates = []
    for b in blobs:
        bw   = b['x2'] - b['x1'] + 1
        bh   = b['y2'] - b['y1'] + 1
        ar   = bw / bh
        fill = b['pixel_count'] / (bw * bh)
        if ar < 0.7 or ar > 1.4:        # omr_warp.cpp:480
            continue
        if fill < 0.85:                  # omr_warp.cpp:481
            continue
        if b['mean_gray'] > 80.0:        # omr_warp.cpp:482 — must be very dark bbox
            continue
        candidates.append(b)

    print(f"[FindMarkerCorners] Total blobs: {len(blobs)}, "
          f"After filter: {len(candidates)} (thresh={thresh})")

    if len(candidates) < 4:              # omr_warp.cpp:489
        print("[FindMarkerCorners] Not enough candidates (<4)")
        return None

    # omr_warp.cpp:494-498  sort by area descending
    candidates.sort(key=lambda b: b['pixel_count'], reverse=True)

    # omr_warp.cpp:503-507  keep only blobs >= 50% of the largest blob's area
    area_threshold = candidates[0]['pixel_count'] // 2
    big_markers = [b for b in candidates if b['pixel_count'] >= area_threshold]

    print(f"[FindMarkerCorners] Area threshold: {area_threshold} "
          f"(50% of largest={candidates[0]['pixel_count']}), "
          f"kept {len(big_markers)} of {len(candidates)}")
    for i, b in enumerate(big_markers):
        bw = b['x2'] - b['x1'] + 1
        bh = b['y2'] - b['y1'] + 1
        print(f"[FindMarkerCorners]   #{i}: area={b['pixel_count']}, "
              f"pos=({b['cx']:.0f},{b['cy']:.0f}), "
              f"size={bw}x{bh}, gray={b['mean_gray']:.0f}")

    candidates = big_markers
    if len(candidates) > 20:             # omr_warp.cpp:519-520
        candidates = candidates[:20]

    # omr_warp.cpp:522-527  centroid of all remaining candidates
    cx = sum(b['cx'] for b in candidates) / len(candidates)
    cy = sum(b['cy'] for b in candidates) / len(candidates)
    print(f"[FindMarkerCorners] Centroid: ({cx:.1f}, {cy:.1f})")

    # omr_warp.cpp:530-539  quadrant assignment + pick farthest from centroid
    #
    # q bits:  bit0 = (blob.cx > cx),  bit1 = (blob.cy > cy)
    # q=0 → upper-left  → slot 0 (TL)
    # q=1 → upper-right → slot 1 (TR)
    # q=3 → lower-right → slot 2 (BR)   ← q=3 maps to slot 2
    # q=2 → lower-left  → slot 3 (BL)   ← q=2 maps to slot 3
    quad   = [None, None, None, None]
    quad_d = [-1.0,  -1.0,  -1.0,  -1.0]

    for b in candidates:
        q    = (1 if b['cx'] > cx else 0) + (2 if b['cy'] > cy else 0)
        slot = 2 if q == 3 else (3 if q == 2 else q)  # omr_warp.cpp:536
        dx   = b['cx'] - cx
        dy   = b['cy'] - cy
        d    = dx * dx + dy * dy
        if d > quad_d[slot]:
            quad_d[slot] = d
            quad[slot]   = b

    if any(q is None for q in quad):    # omr_warp.cpp:542
        return None

    corners = np.array([
        [quad[0]['cx'], quad[0]['cy']],  # TL  omr_warp.cpp:544
        [quad[1]['cx'], quad[1]['cy']],  # TR  omr_warp.cpp:545
        [quad[2]['cx'], quad[2]['cy']],  # BR  omr_warp.cpp:546
        [quad[3]['cx'], quad[3]['cy']],  # BL  omr_warp.cpp:547
    ], dtype=np.float64)

    print(f"[FindMarkerCorners] Result: "
          f"TL({corners[0,0]:.1f},{corners[0,1]:.1f}) "
          f"TR({corners[1,0]:.1f},{corners[1,1]:.1f}) "
          f"BR({corners[2,0]:.1f},{corners[2,1]:.1f}) "
          f"BL({corners[3,0]:.1f},{corners[3,1]:.1f})")
    return corners


# ═══════════════════════════════════════════════════════════════
# 8. LAYER 2 — PAPER CORNER DETECTION  (omr_warp.cpp:554-613)
# ═══════════════════════════════════════════════════════════════

def find_paper_corners_py(gray: np.ndarray) -> Optional[np.ndarray]:
    """
    omr_warp.cpp:554-613  FindPaperCorners — LAYER 2 fallback.

    Detects the white paper boundary as the largest white blob.

    Pipeline:
      1. GaussianBlur5 × 2  (≈ 7×7 blur, approximates Android's 7×7 kernel)
      2. AdaptiveOtsuThreshold  (handles dark images by histogram equalisation)
      3. Binary: pixel >= thresh → 255 (paper=white), < thresh → 0
      4. MorphClose × 4  (fills gaps, approximates Android's 9×9 MorphClose)
      5. FindBlobs  min_area = n/20  (paper must be ≥ 5% of frame)
      6. Largest blob = paper
      7. Diagonal extremes within the largest blob's bounding box:
           TL = argmin(x+y)   — closest to image top-left
           TR = argmax(x−y)   — most right-and-up
           BR = argmax(x+y)   — farthest from image top-left
           BL = argmin(x−y)   — most left-and-down

    Returns:
        (4, 2) float64 array [TL, TR, BR, BL] in downscaled-image coords.
        None if no blob found or bounding box is empty.
    """
    h, w = gray.shape
    n    = w * h

    # omr_warp.cpp:562-563  two passes of GaussianBlur5 (≈ 7×7)
    blurred = gaussian_blur5(gray)
    blurred = gaussian_blur5(blurred)

    # omr_warp.cpp:567  AdaptiveOtsuThreshold
    thresh = adaptive_otsu_threshold(blurred)

    # omr_warp.cpp:568  Binary: paper (bright) → 255, dark → 0
    binary = np.where(blurred >= thresh, np.uint8(255), np.uint8(0))

    # omr_warp.cpp:571-574  MORPH_CLOSE × 4
    for _ in range(4):
        binary = morph_close(binary)

    # omr_warp.cpp:577  min_area = n/20, max_area = n (entire frame)
    blobs = find_blobs(binary, None, min_area=n // 20, max_area=n)
    print(f"[FindPaperCorners] Found {len(blobs)} blobs")

    if not blobs:
        print(f"[FindPaperCorners] No white blobs found! Threshold was {thresh}")
        return None

    # omr_warp.cpp:584-588  largest blob = paper
    largest = max(blobs, key=lambda b: b['pixel_count'])
    print(f"[FindPaperCorners] Largest blob: {largest['pixel_count']} pixels, "
          f"bbox=({largest['x1']},{largest['y1']})-"
          f"({largest['x2']},{largest['y2']})")

    # omr_warp.cpp:595-607  scan only the largest blob's bounding box pixels
    x1, y1 = largest['x1'], largest['y1']
    x2, y2 = largest['x2'], largest['y2']

    roi_bin = binary[y1:y2 + 1, x1:x2 + 1]
    ys_rel, xs_rel = np.where(roi_bin == 255)

    if len(ys_rel) == 0:
        return None

    xs_abs = (xs_rel + x1).astype(np.int32)
    ys_abs = (ys_rel + y1).astype(np.int32)

    sums  = xs_abs + ys_abs   # x + y  (omr_warp.cpp:602: int s = x + y)
    diffs = xs_abs - ys_abs   # x − y  (omr_warp.cpp:602: int d = x - y)

    tl_idx = int(np.argmin(sums))    # omr_warp.cpp:603: if (s < sum_tl)
    br_idx = int(np.argmax(sums))    # omr_warp.cpp:604: if (s > sum_br)
    tr_idx = int(np.argmax(diffs))   # omr_warp.cpp:605: if (d > diff_tr)
    bl_idx = int(np.argmin(diffs))   # omr_warp.cpp:606: if (d < diff_bl)

    corners = np.array([
        [float(xs_abs[tl_idx]), float(ys_abs[tl_idx])],  # TL  omr_warp.cpp:609
        [float(xs_abs[tr_idx]), float(ys_abs[tr_idx])],  # TR
        [float(xs_abs[br_idx]), float(ys_abs[br_idx])],  # BR
        [float(xs_abs[bl_idx]), float(ys_abs[bl_idx])],  # BL
    ], dtype=np.float64)

    print(f"[FindPaperCorners] Success! Corners: "
          f"TL({corners[0,0]:.1f},{corners[0,1]:.1f}) "
          f"TR({corners[1,0]:.1f},{corners[1,1]:.1f}) "
          f"BR({corners[2,0]:.1f},{corners[2,1]:.1f}) "
          f"BL({corners[3,0]:.1f},{corners[3,1]:.1f})")
    return corners


# ═══════════════════════════════════════════════════════════════
# 9. MAIN ENTRY POINT  (omr_warp.cpp:635-709)
# ═══════════════════════════════════════════════════════════════

def normalize_sheet_py(
    img_bgr: np.ndarray,
) -> Tuple[Optional[np.ndarray], Optional[np.ndarray], int]:
    """
    Reproduces NormalizeSheet() from omr_warp.cpp:635-709.

    Args:
        img_bgr: numpy (H, W, 3) BGR — standard cv2.imread output.

    Returns:
        warped: numpy (2400, 1700, 3) BGR  — perspective-corrected sheet.
                None only if input is invalid.
        H:      numpy (3, 3) forward homography  src_full_coords → dst_1700×2400.
                None if detection failed (fallback resize path).
        layer:  1 = marker corners (Layer 1)
                2 = paper corners  (Layer 2 fallback)
                0 = both failed, simple resize used

    Corner layout throughout (indices 0..3):
        [TL, TR, BR, BL]

    Homography dst corner mapping (omr_warp.cpp:342-343):
        TL → (0,      0     )
        TR → (1700,   0     )
        BR → (1700,   2400  )
        BL → (0,      2400  )

    No coordinate normalization is applied before the DLT solve.
    omr_warp.cpp:347-363: coordinates are used as-is in the 8×8 linear system.

    Notes:
        - Analysis runs at fixed width 800px; height is proportional.
          omr_warp.cpp:644-645: kAnalysisH = src_h * 800 / src_w  (integer floor).
        - Both layers use the SAME gray_small image (omr_warp.cpp:672).
        - No padding is added before processing.
        - No orientation-dependent branching: portrait and landscape are handled
          identically — the 800-wide downscale simply gives a short/tall image.
        - On total failure: returns simple nearest-neighbour resize (layer=0)
          and does NOT raise an exception (omr_warp.cpp:704-706).
    """
    src_h, src_w = img_bgr.shape[:2]
    if src_w <= 0 or src_h <= 0:
        return None, None, 0

    print(f"[NormalizeSheet] Input: {src_w}x{src_h} -> {K_TARGET_W}x{K_TARGET_H}")

    # C++ takes RGBA; add alpha=255 to match channel layout used for grayscale conversion
    rgba = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGBA)

    # omr_warp.cpp:644-651: prepare 800-wide grayscale analysis image
    # Integer floor division to match:  kAnalysisH = src_h * kAnalysisW / src_w
    k_analysis_h = src_h * K_ANALYSIS_W // src_w

    # ResizeRgba (nearest-neighbour) then RgbaToGray
    rgba_small = cv2.resize(rgba, (K_ANALYSIS_W, k_analysis_h),
                            interpolation=cv2.INTER_NEAREST)
    gray_small = rgba_to_gray(rgba_small)   # exact BT.601 integer formula

    # omr_warp.cpp:654-682: two-layer detection on SAME gray_small
    corners_small: Optional[np.ndarray] = None
    layer = 0

    # ── Layer 1: FindMarkerCorners ──────────────────────────────
    c = find_marker_corners_py(gray_small)
    if c is not None and corners_look_valid(c, K_ANALYSIS_W, k_analysis_h):
        print("[NormalizeSheet] Layer 1 (MarkerCorners) succeeded")
        corners_small = c
        layer = 1
    elif c is not None:
        print("[NormalizeSheet] Layer 1 (MarkerCorners) corners invalid")
    else:
        print("[NormalizeSheet] Layer 1 (MarkerCorners) failed")

    # ── Layer 2 fallback: FindPaperCorners ──────────────────────
    # Uses the EXACT SAME gray_small — no re-preprocessing from original
    if corners_small is None:
        c = find_paper_corners_py(gray_small)
        if c is not None and corners_look_valid(c, K_ANALYSIS_W, k_analysis_h):
            print("[NormalizeSheet] Layer 2 (PaperCorners) succeeded")
            corners_small = c
            layer = 2
        elif c is not None:
            print("[NormalizeSheet] Layer 2 (PaperCorners) corners invalid")
        else:
            print("[NormalizeSheet] Layer 2 (PaperCorners) failed")

    if corners_small is None:
        # omr_warp.cpp:704-706: both failed → simple resize, return true (no exception)
        print("[NormalizeSheet] Using fallback: simple resize")
        warped = cv2.resize(img_bgr, (K_TARGET_W, K_TARGET_H),
                            interpolation=cv2.INTER_NEAREST)
        return warped, None, 0

    # omr_warp.cpp:686-690: scale corners from analysis space to full resolution
    corners_full = corners_small.copy()
    corners_full[:, 0] = corners_full[:, 0] * src_w / K_ANALYSIS_W
    corners_full[:, 1] = corners_full[:, 1] * src_h / k_analysis_h

    # omr_warp.cpp:693: ComputeHomography(corners_full, kTargetW, kTargetH, H)
    # dst layout: TL→(0,0), TR→(W,0), BR→(W,H), BL→(0,H)  (omr_warp.cpp:342-343)
    dst_pts = np.array([
        [0.0,         0.0        ],   # TL → (0, 0)
        [K_TARGET_W,  0.0        ],   # TR → (1700, 0)
        [K_TARGET_W,  K_TARGET_H ],   # BR → (1700, 2400)
        [0.0,         K_TARGET_H ],   # BL → (0, 2400)
    ], dtype=np.float32)

    src_pts = corners_full.astype(np.float32)

    # cv2.getPerspectiveTransform solves the same 8×8 DLT system as ComputeHomography
    # (no coordinate normalisation — same as C++ omr_warp.cpp:345-366)
    H = cv2.getPerspectiveTransform(src_pts, dst_pts)

    # omr_warp.cpp:694-696: InvertHomography + WarpRgba (bilinear inverse mapping)
    # cv2.warpPerspective computes Hinv internally and samples bilinearly.
    # BORDER_REPLICATE matches C++ WarpRgba clamp:  max(0,min(x0,sw-1))  omr_warp.cpp:413-416
    warped_rgba = cv2.warpPerspective(
        rgba, H, (K_TARGET_W, K_TARGET_H),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_REPLICATE,
    )
    warped_bgr = cv2.cvtColor(warped_rgba, cv2.COLOR_RGBA2BGR)

    print("[NormalizeSheet] Perspective warp succeeded")
    return warped_bgr, H, layer


# ═══════════════════════════════════════════════════════════════
# 10. TEST / VERIFICATION RUNNER  (Section 6)
# ═══════════════════════════════════════════════════════════════

def run_test(img_path: str, label: str, output_dir: str = ".") -> None:
    """
    Run normalize_sheet_py on one image, print diagnostics, save side-by-side figure.

    Prints:
      - Layer used (1=marker / 2=paper / 0=fallback)
      - 4 corner coordinates in original-image space (TL/TR/BR/BL)
      - det(H)  — must be != 0 for a valid warp

    Saves: <output_dir>/<label>_result.jpg
      Left half:  original image with corners drawn + numbered in C++ order
      Right half: warped 1700×2400 output
    """
    img = cv2.imread(img_path)
    if img is None:
        print(f"[Test] Cannot load: {img_path}")
        return

    print(f"\n{'='*60}")
    print(f"[Test] {label}: {os.path.basename(img_path)} "
          f"({img.shape[1]}x{img.shape[0]})")
    print(f"{'='*60}")

    warped, H, layer = normalize_sheet_py(img)

    print(f"\n{'─'*40}")
    print(f"[Result] Layer used: {layer}  "
          f"({'marker corners' if layer==1 else 'paper corners' if layer==2 else 'fallback resize'})")

    corners_full = None
    if H is not None:
        det = float(np.linalg.det(H))
        print(f"[Result] det(H) = {det:.6f}  (should be far from 0)")

        # Re-detect to get corners for visualisation (same logic, same gray_small)
        src_h, src_w = img.shape[:2]
        k_analysis_h = src_h * K_ANALYSIS_W // src_w
        rgba_s = cv2.resize(cv2.cvtColor(img, cv2.COLOR_BGR2RGBA),
                            (K_ANALYSIS_W, k_analysis_h),
                            interpolation=cv2.INTER_NEAREST)
        gs = rgba_to_gray(rgba_s)

        c_s = find_marker_corners_py(gs)
        if c_s is None or not corners_look_valid(c_s, K_ANALYSIS_W, k_analysis_h):
            c_s = find_paper_corners_py(gs)

        if c_s is not None:
            corners_full = c_s.copy()
            corners_full[:, 0] *= src_w / K_ANALYSIS_W
            corners_full[:, 1] *= src_h / k_analysis_h

            lbl_names = ['0:TL', '1:TR', '2:BR', '3:BL']
            for name, pt in zip(lbl_names, corners_full):
                print(f"[Result] Corner {name}: ({pt[0]:.1f}, {pt[1]:.1f})")
    else:
        print("[Result] H = None (fallback resize path)")

    # ── Build visualisation ──────────────────────────────────────────
    vis = img.copy()
    if corners_full is not None:
        colors = [(0, 220, 0), (220, 0, 0), (0, 0, 220), (220, 220, 0)]   # G B R Y
        pts_int = corners_full.astype(np.int32)
        cv2.polylines(vis, [pts_int.reshape(-1, 1, 2)], True, (0, 255, 0), 4)
        for i, (pt, col) in enumerate(zip(pts_int, colors)):
            cv2.circle(vis, tuple(pt), 18, col, -1)
            cv2.putText(vis, str(i), (int(pt[0]) + 6, int(pt[1]) - 8),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.0, col, 3)

    # Scale both halves to the same display height
    disp_h = K_TARGET_H // 3    # ~800px display height
    disp_w_left  = int(img.shape[1] * disp_h / img.shape[0])
    disp_w_right = int(K_TARGET_W   * disp_h / K_TARGET_H)
    left  = cv2.resize(vis,    (disp_w_left,  disp_h))
    right = cv2.resize(warped, (disp_w_right, disp_h))
    combined = np.hstack([left, right])

    out_path = os.path.join(output_dir, f"{label}_result.jpg")
    cv2.imwrite(out_path, combined)
    print(f"[Result] Saved: {out_path}")
    print(f"{'─'*40}")


# ═══════════════════════════════════════════════════════════════
# 11. QUICK COLAB DEMO
# ═══════════════════════════════════════════════════════════════

def demo_colab(img_path: str) -> None:
    """
    Colab-friendly wrapper: loads image, runs detection, displays result inline.

    Usage in Colab:
        from paper_detection_reference import demo_colab
        demo_colab("path/to/sheet.jpg")
    """
    try:
        from google.colab.patches import cv2_imshow
        show_fn = cv2_imshow
    except ImportError:
        # Fallback: save to disk
        show_fn = None

    img = cv2.imread(img_path)
    if img is None:
        print(f"Cannot load: {img_path}")
        return

    warped, H, layer = normalize_sheet_py(img)

    src_h, src_w = img.shape[:2]
    k_analysis_h = src_h * K_ANALYSIS_W // src_w
    rgba_s = cv2.resize(cv2.cvtColor(img, cv2.COLOR_BGR2RGBA),
                        (K_ANALYSIS_W, k_analysis_h),
                        interpolation=cv2.INTER_NEAREST)
    gs = rgba_to_gray(rgba_s)
    c_s = find_marker_corners_py(gs)
    if c_s is None or not corners_look_valid(c_s, K_ANALYSIS_W, k_analysis_h):
        c_s = find_paper_corners_py(gs)

    vis = img.copy()
    if c_s is not None:
        cf = c_s.copy()
        cf[:, 0] *= src_w / K_ANALYSIS_W
        cf[:, 1] *= src_h / k_analysis_h
        pts = cf.astype(np.int32)
        cv2.polylines(vis, [pts.reshape(-1, 1, 2)], True, (0, 255, 0), 4)
        for i, pt in enumerate(pts):
            cv2.circle(vis, tuple(pt), 18, (0, 0, 255), -1)
            cv2.putText(vis, str(i), (int(pt[0])+6, int(pt[1])-8),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 255), 3)

    dh = 600
    left  = cv2.resize(vis,    (int(img.shape[1]*dh/img.shape[0]), dh))
    right = cv2.resize(warped, (int(K_TARGET_W*dh/K_TARGET_H),     dh))
    combined = np.hstack([left, right])

    print(f"Layer: {layer}  |  H det={float(np.linalg.det(H)):.4f}" if H is not None
          else "Layer: 0 (fallback resize)")

    if show_fn:
        show_fn(combined)
    else:
        cv2.imwrite("demo_result.jpg", combined)
        print("Saved: demo_result.jpg")


# ═══════════════════════════════════════════════════════════════
# __main__ — run on test images from full_label_yolo/images/
# ═══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    import sys

    if len(sys.argv) > 1:
        for p in sys.argv[1:]:
            run_test(p, os.path.splitext(os.path.basename(p))[0])
    else:
        # Default: first image (standard) + a later one (potentially challenging)
        base = os.path.join(os.path.dirname(__file__), "full_label_yolo", "images")
        pairs = [
            (os.path.join(base, "IMG_20260320_153649.jpg"), "standard"),
            (os.path.join(base, "IMG_20260320_153715.jpg"), "challenging"),
        ]
        for path, label in pairs:
            if os.path.exists(path):
                run_test(path, label)
            else:
                print(f"[Test] Not found: {path}")
