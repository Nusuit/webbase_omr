package com.gradesnap.omr

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Port từ omr_normalize.py / src/core/omr_warp.cpp (NormalizeSheet).
 * Nhận ảnh thô BGR → tìm 4 góc tờ giấy → warp perspective → chuẩn hóa 1700×2400.
 *
 * Re-ported 2026-05-30 to match the C++ reference used by the Web/WASM pipeline:
 *   - corner detection runs on an 800px-wide analysis image (not full-res);
 *   - marker filter is tight (aspect 0.7–1.4, fill ≥0.85, mean_gray <80, area bounded);
 *   - big-marker grouping keeps blobs ≥50% of the largest blob area;
 *   - CornersLookValid rejects degenerate quads before warping.
 * The previous loose port (area≥150, fill≥0.4, gray<160, no downscale, no validation)
 * failed on real tilted photos and fell back to a plain resize, breaking OMR grids.
 */
object NormalizePaper {
    const val TARGET_W = 1700
    const val TARGET_H = 2400
    private const val ANALYSIS_W = 800

    /** Corners returned in [TL, TR, BR, BL] order, in analysis-image coordinates. */
    fun normalize(img: Mat): Mat {
        val analysisH = ((img.rows().toLong() * ANALYSIS_W) / img.cols()).toInt().coerceAtLeast(1)
        val small = Mat()
        Imgproc.resize(img, small, Size(ANALYSIS_W.toDouble(), analysisH.toDouble()))
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)

        var corners = findMarkerCorners(gray)?.takeIf { cornersLookValid(it, ANALYSIS_W, analysisH) }
        if (corners == null) {
            corners = findPaperCorners(gray)?.takeIf { cornersLookValid(it, ANALYSIS_W, analysisH) }
        }

        small.release(); gray.release()

        if (corners == null) {
            // Fallback: no reliable corners → plain resize (grids will be off, but no crash).
            val resized = Mat()
            Imgproc.resize(img, resized, Size(TARGET_W.toDouble(), TARGET_H.toDouble()))
            return resized
        }

        // Map corners from 800px analysis scale back to full-resolution coordinates.
        val sx = img.cols().toDouble() / ANALYSIS_W
        val sy = img.rows().toDouble() / analysisH
        val src = MatOfPoint2f(
            Point(corners[0].x * sx, corners[0].y * sy),  // TL
            Point(corners[1].x * sx, corners[1].y * sy),  // TR
            Point(corners[2].x * sx, corners[2].y * sy),  // BR
            Point(corners[3].x * sx, corners[3].y * sy)   // BL
        )
        val dst = MatOfPoint2f(
            Point(0.0,            0.0),
            Point(TARGET_W - 1.0, 0.0),
            Point(TARGET_W - 1.0, TARGET_H - 1.0),
            Point(0.0,            TARGET_H - 1.0)
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(img, warped, m, Size(TARGET_W.toDouble(), TARGET_H.toDouble()))
        src.release(); dst.release(); m.release()
        return warped
    }

    // ─── Sanity check: are the 4 corners arranged reasonably? (port CornersLookValid) ─
    private fun cornersLookValid(c: Array<Point>, w: Int, h: Int): Boolean {
        val minSpanX = w * 0.15
        val minSpanY = h * 0.15
        if (c[1].x - c[0].x < minSpanX) return false  // TR.x − TL.x
        if (c[2].x - c[3].x < minSpanX) return false  // BR.x − BL.x
        if (c[2].y - c[1].y < minSpanY) return false  // BR.y − TR.y
        if (c[3].y - c[0].y < minSpanY) return false  // BL.y − TL.y
        return true
    }

    // ─── LAYER 1: 4 black marker corners (port C++ FindMarkerCorners) ──────────────
    // Returns [TL, TR, BR, BL] or null.
    private fun findMarkerCorners(gray: Mat): Array<Point>? {
        val n = gray.rows() * gray.cols()
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val bin = Mat()
        Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        blur.release()

        // MORPH_OPEN ×2 (remove thin lines, keep solid dark squares)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN, kernel)

        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val nlab = Imgproc.connectedComponentsWithStats(bin, labels, stats, centroids, 8, CvType.CV_32S)
        bin.release(); labels.release()

        val maxArea = minOf(n / 4, 2000)

        data class Cand(val area: Int, val cx: Double, val cy: Double)
        val cands = ArrayList<Cand>()
        for (i in 1 until nlab) {
            val area = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
            if (area < 50 || area > maxArea) continue
            val x  = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y  = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val bw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val bh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            if (bw <= 0 || bh <= 0) continue
            val ar = bw.toDouble() / bh
            if (ar < 0.7 || ar > 1.4) continue
            val fill = area.toDouble() / (bw * bh)
            if (fill < 0.85) continue
            val roi = gray.submat(Rect(x, y, bw, bh))
            val meanGray = Core.mean(roi).`val`[0]
            roi.release()
            // Markers must be a dark solid square. C++ reference used <80 (expects
            // pure-black markers gray 28-57), but Redmi photos on dark backgrounds
            // expose the printed markers at gray ~85-100, so <80 rejected all four
            // corners and the warp fell back to misaligned paper corners. <130 keeps
            // the real markers while aspect/fill still reject text and shadows.
            // Sweep over the 179-sheet set: <80 → 130/179 valid quads, <130 → 174/179.
            if (meanGray > 130.0) continue
            cands.add(Cand(area, centroids.get(i, 0)[0], centroids.get(i, 1)[0]))
        }
        stats.release(); centroids.release()

        if (cands.size < 4) return null

        cands.sortByDescending { it.area }
        // Keep only blobs ≥50% of the largest → separates big corner markers from small inner ones.
        val areaThreshold = cands[0].area / 2
        var big = cands.filter { it.area >= areaThreshold }
        if (big.size > 20) big = big.take(20)

        val cx = big.map { it.cx }.average()
        val cy = big.map { it.cy }.average()

        // Quadrant slots: TL=0, TR=1, BR=2, BL=3 (matches C++ remap)
        val quad = arrayOfNulls<Cand>(4)
        val quadD = doubleArrayOf(-1.0, -1.0, -1.0, -1.0)
        for (b in big) {
            val q = (if (b.cx > cx) 1 else 0) + (if (b.cy > cy) 2 else 0)  // 0=TL,1=TR,2=BL,3=BR
            val slot = when (q) { 3 -> 2; 2 -> 3; else -> q }
            val dx = b.cx - cx; val dy = b.cy - cy
            val d = dx * dx + dy * dy
            if (d > quadD[slot]) { quadD[slot] = d; quad[slot] = b }
        }
        if (quad[0] == null || quad[1] == null || quad[2] == null || quad[3] == null) return null

        return arrayOf(
            Point(quad[0]!!.cx, quad[0]!!.cy),
            Point(quad[1]!!.cx, quad[1]!!.cy),
            Point(quad[2]!!.cx, quad[2]!!.cy),
            Point(quad[3]!!.cx, quad[3]!!.cy)
        )
    }

    // ─── LAYER 2: paper boundary fallback (port C++ FindPaperCorners) ──────────────
    // Returns [TL, TR, BR, BL] from diagonal extremes of the largest white blob.
    private fun findPaperCorners(gray: Mat): Array<Point>? {
        val n = gray.rows() * gray.cols()
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(7.0, 7.0), 0.0)

        // Adaptive Otsu: if image is dark (answer key), equalize histogram and rescale.
        val tmp = Mat()
        var thr = Imgproc.threshold(blur, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        if (thr < 130.0) {
            val eq = Mat(); Imgproc.equalizeHist(blur, eq)
            val t2 = Imgproc.threshold(eq, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            eq.release()
            thr = t2 * 0.6
        }
        tmp.release()

        val bin = Mat()
        Imgproc.threshold(blur, bin, thr, 255.0, Imgproc.THRESH_BINARY)  // paper (light) → 255
        blur.release()

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(bin, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        bin.release()
        if (contours.isEmpty()) return null

        val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        if (Imgproc.contourArea(largest) < n / 20.0) return null

        var tl: Point? = null; var tr: Point? = null; var br: Point? = null; var bl: Point? = null
        var sMin = Double.MAX_VALUE; var sMax = -Double.MAX_VALUE
        var dMax = -Double.MAX_VALUE; var dMin = Double.MAX_VALUE
        for (p in largest.toArray()) {
            val s = p.x + p.y; val d = p.x - p.y
            if (s < sMin) { sMin = s; tl = p }
            if (s > sMax) { sMax = s; br = p }
            if (d > dMax) { dMax = d; tr = p }
            if (d < dMin) { dMin = d; bl = p }
        }
        if (tl == null || tr == null || br == null || bl == null) return null
        return arrayOf(tl, tr, br, bl)
    }
}
